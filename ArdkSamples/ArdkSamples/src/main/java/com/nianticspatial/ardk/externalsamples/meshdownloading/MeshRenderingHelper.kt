package com.nianticspatial.ardk.externalsamples

import android.graphics.BitmapFactory
import android.opengl.Matrix
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.nianticlabs.ardk.MeshData
import com.nianticlabs.ardk.MeshDownloaderData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import io.github.sceneview.ar.node.PoseNode
import io.github.sceneview.node.MeshNode
import io.github.sceneview.loaders.MaterialLoader
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import com.google.android.filament.Box
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import androidx.compose.ui.graphics.Color
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import kotlin.math.abs

/**
 * A helper class that processes ARDK mesh data and generates MeshNodes from that data
 */
class MeshRenderingHelper(
    private val engine: Engine,
    private val materialLoader: MaterialLoader
) {

    companion object {
        private const val TAG = "MeshRenderer"
    }

    // Class to hold data for each mesh chunk
    private data class RenderableMeshChunk(
        val meshData: MeshData,
        val modelMatrix: FloatArray,
        val textureData: ByteArray? = null,
        var indexCount: Int = 0,
    )

    // Container for all mesh chunks
    private val renderableMeshChunks = mutableListOf<RenderableMeshChunk>()

    // Flag for reloading all chunks, mostly for if we receive a new mesh
    private var _needsFullMeshReload = false
    val needsFullMeshReload: Boolean get() = _needsFullMeshReload

    /**
     * Sets the mesh chunks to be rendered. Used for mesh download data
     *
     * @param meshChunksData List of the mesh chunks to be rendered.
     */
    fun setDownloadedMeshChunks(meshChunksData: Array<MeshDownloaderData>?) {

        renderableMeshChunks.clear()

        if (meshChunksData.isNullOrEmpty()) {
            Log.d(TAG, "setDownloadedMeshChunks(): No mesh chunks provided.")
            return
        }

        meshChunksData.forEach { chunkData ->
            val renderableChunk = RenderableMeshChunk(
                meshData = chunkData.meshData,
                modelMatrix = chunkData.transform,
                textureData = chunkData.imageData,
            )
            renderableMeshChunks.add(renderableChunk)
        }

        // Set a flag to indicate we need to recreate the meshNodes
        _needsFullMeshReload = true
    }

    /**
     * Sets the mesh chunks to be rendered. Used for live meshing data
     *
     * @param meshChunksData List of the mesh chunks to be rendered.
     */
    fun setLiveMeshChunks(meshChunksData: Collection<MeshData>) {

        renderableMeshChunks.clear()

        val identityModelMatrix = FloatArray(16)
        Matrix.setIdentityM(identityModelMatrix, 0)
        meshChunksData.forEach { mesh ->
            val renderableChunk = RenderableMeshChunk(
                meshData = mesh,
                modelMatrix = identityModelMatrix
            )
            renderableMeshChunks.add(renderableChunk)
        }
        // Set a flag to indicate we need to recreate the MeshNodes
        _needsFullMeshReload = true
    }

    /**
     * Creates MeshNodes for the loaded mesh chunks and adds them to the active scene.
     */
    fun createMeshNodes(parentNode: PoseNode) {
        if (renderableMeshChunks.isEmpty()) {
            Log.w(TAG, "createMeshNodes: No chunks to load.")
            return
        }

        renderableMeshChunks.forEachIndexed { index, chunk ->
            val meshData = chunk.meshData

            // Create the vertex buffer data combining the vertex and UV data
            val originalVertices = meshData.vertices
            val correctedVertices = FloatArray(originalVertices.size) { i ->
                when (i % 3) {
                    0 -> originalVertices[i]       // X
                    1 -> -originalVertices[i]      // -Y
                    else -> -originalVertices[i]   // -Z
                }
            }

            val uvs = meshData.uvs
            val hasUvs = uvs != null && uvs.isNotEmpty()
            var correctedUvs: FloatArray? = null
            if (hasUvs) {
                correctedUvs = FloatArray(uvs.size) { i ->
                    uvs[i]
                }
            } else {
                Log.d(TAG, "Chunk $index: No UV data.")
            }

            // We need to convert the incoming float arrays into VertexBuffers
            val vertexCount = originalVertices.size / 3
            val vertexStride = 3 * 4 // (X,Y,Z) * 4 bytes
            val uvStride = 2 * 4 // (U,V) * 4 bytes
            val correctedVertexBuffer = ByteBuffer.allocateDirect(vertexCount * vertexStride)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

            val correctedUVBuffer = ByteBuffer.allocateDirect(vertexCount * uvStride)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

            var maxExtents = Float3(x = Float.MIN_VALUE, y = Float.MIN_VALUE, z = Float.MIN_VALUE)

            for (i in 0 until vertexCount) {
                correctedVertexBuffer.put(correctedVertices, i * 3, 3) // Put X, Y, Z

                // We need the extents of the mesh for the bounding box
                val cX = abs(correctedVertices[i * 3 + 0])
                val cY = abs(correctedVertices[i * 3 + 1])
                val cZ = abs(correctedVertices[i * 3 + 2])

                if (cX > maxExtents.x)
                    maxExtents.x = cX

                if (cY > maxExtents.y)
                    maxExtents.y = cY

                if (cZ > maxExtents.z)
                    maxExtents.z = cZ
            }
            correctedVertexBuffer.position(0)

            // Create the UV buffer
            if (hasUvs) {
                for (i in 0 until vertexCount) {
                    correctedUVBuffer.put(correctedUvs, i * 2, 2) // Put U, V
                }
                correctedUVBuffer.position(0)
            }

            // Build the combined VertexBuffer (with both attributes if they exist)
            val vertexBufferBuilder = VertexBuffer.Builder()
                .bufferCount(if (hasUvs) 2 else 1)
                .vertexCount(vertexCount)
                .attribute(
                    VertexBuffer.VertexAttribute.POSITION,
                    0,
                    VertexBuffer.AttributeType.FLOAT3,
                    0,
                    vertexStride
                )

            if (hasUvs) {
                vertexBufferBuilder.attribute(
                    VertexBuffer.VertexAttribute.UV0,
                    1,
                    VertexBuffer.AttributeType.FLOAT2,
                    0,
                    uvStride
                )
            }

            val meshVertexBuffer = vertexBufferBuilder.build(engine)
            meshVertexBuffer.setBufferAt(engine, 0, correctedVertexBuffer)
            meshVertexBuffer.setBufferAt(engine, 1, correctedUVBuffer)


            // Process the mesh indices for this chunk
            chunk.indexCount = meshData.indices.size

            // IndexBuffer expects an untyped ByteBuffer as input, so we need to convert the intArray from the meshData into a byteBuffer
            val indicesBuffer = ByteBuffer.allocateDirect(chunk.indexCount * 4)
                .order(ByteOrder.nativeOrder())

            for (i in 0 until chunk.indexCount) {
                indicesBuffer.putInt(meshData.indices[i]) // Put the index into the byte array
            }
            indicesBuffer.position(0)

            val meshIndexBuffer = IndexBuffer.Builder()
                .indexCount(chunk.indexCount)
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .build(engine)

            meshIndexBuffer.setBuffer(engine, indicesBuffer)


            // Create the texture for this chunk
            var materialInstance: MaterialInstance? = null

            if (chunk.textureData == null) {
                Log.e(TAG, "Chunk $index: No texture data. Using dummy.")
                materialInstance = materialLoader.createColorInstance(Color.White)
            } else {
                val bitmap =
                    BitmapFactory.decodeByteArray(chunk.textureData, 0, chunk.textureData.size)
                if (bitmap != null) {

                    val texture = Texture.Builder()
                        .width(bitmap.width)
                        .height(bitmap.height)
                        .sampler(Texture.Sampler.SAMPLER_2D)
                        .format(Texture.InternalFormat.SRGB8_A8) // Use SRGB for color textures
                        .levels(0xff) // Use all mipmap levels
                        .build(engine)

                    val byteBuffer = ByteBuffer.allocate(bitmap.byteCount)
                    bitmap.copyPixelsToBuffer(byteBuffer)
                    byteBuffer.rewind()

                    texture.setImage(
                        engine,
                        0,
                        Texture.PixelBufferDescriptor(
                            byteBuffer,
                            Texture.Format.RGBA,
                            Texture.Type.UBYTE
                        )
                    )

                    materialInstance = materialLoader.createImageInstance(texture, TextureSampler())

                    // Clean up the bitmap data
                    bitmap.recycle()
                } else {
                    Log.e(TAG, "Chunk $index: Failed to decode image. Using dummy.")
                    materialInstance = materialLoader.createColorInstance(Color.White)
                }
            }

            // Create the meshNodes from the processed chunk data
            val meshNode = MeshNode(
                engine = engine,
                primitiveType = PrimitiveType.TRIANGLES,
                vertexBuffer = meshVertexBuffer,
                indexBuffer = meshIndexBuffer,
                boundingBox = Box(0f, 0f, 0f, maxExtents.x, maxExtents.y, maxExtents.z),
                materialInstance = materialInstance,
            )

            // Set the relative position of the node from the chunk modelMatrix data
            meshNode.transform = Mat4(
                x = Float4(
                    chunk.modelMatrix[0],
                    chunk.modelMatrix[1],
                    chunk.modelMatrix[2],
                    chunk.modelMatrix[3]
                ),
                y = Float4(
                    chunk.modelMatrix[4],
                    chunk.modelMatrix[5],
                    chunk.modelMatrix[6],
                    chunk.modelMatrix[7]
                ),
                z = Float4(
                    chunk.modelMatrix[8],
                    chunk.modelMatrix[9],
                    chunk.modelMatrix[10],
                    chunk.modelMatrix[11]
                ),
                w = Float4(
                    chunk.modelMatrix[12],
                    chunk.modelMatrix[13],
                    chunk.modelMatrix[14],
                    chunk.modelMatrix[15]
                )
            )

            parentNode.addChildNode(meshNode)
        }

        _needsFullMeshReload = false
    }
}
