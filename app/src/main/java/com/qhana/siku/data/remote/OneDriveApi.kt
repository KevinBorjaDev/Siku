package com.qhana.siku.data.remote

import com.google.gson.annotations.SerializedName
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface OneDriveApi {

    @GET
    suspend fun getDelta(
        @Header("Authorization") token: String,
        @Url url: String
    ): OneDriveResponse

    @GET("me/drive/items/{itemId}")
    suspend fun getItem(
        @Header("Authorization") token: String,
        @Path("itemId") itemId: String,
        @Query("select") select: String = "id,@microsoft.graph.downloadUrl"
    ): OneDriveItem

    /**
     * Sube (crea o reemplaza) un archivo pequeño en la carpeta de la app. La URL se pasa entera
     * con [Url] —igual que en [getDelta]— porque la ruta de Graph lleva `:` literales
     * (`approot:/nombre:/content`) que un `@Path` codificaría.
     */
    @PUT
    suspend fun uploadFile(
        @Header("Authorization") token: String,
        @Url url: String,
        @Body body: RequestBody
    ): OneDriveItem

    /** Descarga el contenido de un archivo de la carpeta de la app. 404 si nunca se subió. */
    @GET
    suspend fun downloadFile(
        @Header("Authorization") token: String,
        @Url url: String
    ): ResponseBody
}

data class OneDriveResponse(
    val value: List<OneDriveItem>,
    @SerializedName("@odata.nextLink")
    val nextLink: String?,
    @SerializedName("@odata.deltaLink")
    val deltaLink: String?
)

data class OneDriveItem(
    val id: String,
    val name: String?, // Puede ser null en items eliminados
    val file: FileFacet?,
    val deleted: DeletedFacet?,
    val size: Long?,
    @SerializedName("@microsoft.graph.downloadUrl")
    val downloadUrl: String?,
    /** Carpeta contenedora (para la ruta relativa de detección de duplicados, v23). */
    val parentReference: ParentReference? = null
)

data class ParentReference(
    /** Formato Graph: "/drive/root:/Music/Sub" (sin URL-encoding en el JSON). */
    val path: String?
)

class FileFacet
class DeletedFacet
