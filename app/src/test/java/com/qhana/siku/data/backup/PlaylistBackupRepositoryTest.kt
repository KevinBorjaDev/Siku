package com.qhana.siku.data.backup

import com.google.gson.Gson
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.local.BackupSongRow
import com.qhana.siku.data.local.PlaylistDao
import com.qhana.siku.data.local.PlaylistEntity
import com.qhana.siku.data.local.PlaylistSongCrossRef
import com.qhana.siku.data.local.SongDao
import com.qhana.siku.data.model.AppError
import com.qhana.siku.data.model.AppResult
import com.qhana.siku.data.remote.OneDriveApi
import com.qhana.siku.data.remote.OneDriveItem
import io.mockk.*
import io.mockk.impl.annotations.RelaxedMockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class PlaylistBackupRepositoryTest {

    @RelaxedMockK lateinit var playlistDao: PlaylistDao
    @RelaxedMockK lateinit var songDao: SongDao
    @RelaxedMockK lateinit var oneDriveApi: OneDriveApi
    @RelaxedMockK lateinit var authManager: AuthManager

    private lateinit var repository: PlaylistBackupRepository
    private val gson = Gson()

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        every { authManager.getBackupAccessToken() } returns flowOf(AuthResult.Success("token"))
        repository = PlaylistBackupRepository(playlistDao, songDao, oneDriveApi, authManager)
    }

    private fun oneDriveItem() = OneDriveItem("id", "f.json", null, null, 0L, null)

    @Test
    fun `export conserva las canciones sin fila en songs y deriva el sourceType del id`() = runTest {
        // Una cross-ref puede apuntar a una canción ya borrada (p.ej. tras desconectar OneDrive):
        // esa entrada debe viajar igual en el backup, o no habría forma de restaurarla.
        coEvery { playlistDao.getAllPlaylists() } returns listOf(PlaylistEntity(1L, "uuid-1", "Rock"))
        coEvery { playlistDao.getBackupRowsForPlaylist(1L) } returns listOf(
            BackupSongRow("local:A/01.flac", "Uno", "Artista", "Album"),
            BackupSongRow("onedrive:XYZ", null, null, null)
        )
        val bodySlot = slot<RequestBody>()
        coEvery { oneDriveApi.uploadFile(any(), any(), capture(bodySlot)) } returns oneDriveItem()

        val result = repository.export()

        assertEquals(1, (result as AppResult.Success).data)
        val uploaded = gson.fromJson(bodySlot.captured.readUtf8(), PlaylistBackupFile::class.java)
        val songs = uploaded.playlists.single().songs
        assertEquals(2, songs.size)
        assertEquals("LOCAL", songs[0].sourceType)
        assertEquals("ONEDRIVE", songs[1].sourceType)
        assertNull(songs[1].title)
    }

    @Test
    fun `import fusiona sin duplicar y cuenta las canciones que faltan`() = runTest {
        givenRemoteBackup(
            BackupPlaylist(
                "uuid-1", "Rock", listOf(
                    backupSong("local:ya-esta"),
                    backupSong("local:nueva"),
                    backupSong("local:desaparecida")
                )
            )
        )
        coEvery { playlistDao.getPlaylistIdByUuid("uuid-1") } returns 7L
        coEvery { playlistDao.getMaxOrderIndex(7L) } returns 3
        coEvery { songDao.existsById("local:ya-esta") } returns true
        coEvery { songDao.existsById("local:nueva") } returns true
        coEvery { songDao.existsById("local:desaparecida") } returns false
        coEvery { songDao.findIdByTitleAndArtist(any(), any()) } returns null
        coEvery { playlistDao.isSongInPlaylist(7L, "local:ya-esta") } returns true
        coEvery { playlistDao.isSongInPlaylist(7L, "local:nueva") } returns false

        val summary = (repository.import() as AppResult.Success).data

        assertEquals(0, summary.playlistsCreated)
        assertEquals(1, summary.playlistsMerged)
        assertEquals(1, summary.songsAdded)
        assertEquals(1, summary.songsMissing)
        // La nueva se añade al final del orden existente (maxOrderIndex + 1), no en la posición 0.
        coVerify(exactly = 1) { playlistDao.addSongToPlaylist(PlaylistSongCrossRef(7L, "local:nueva", 4)) }
        coVerify(exactly = 0) { playlistDao.addSongToPlaylist(match { it.songId == "local:ya-esta" }) }
    }

    @Test
    fun `import crea la playlist si su uuid no existe`() = runTest {
        givenRemoteBackup(BackupPlaylist("uuid-nueva", "Chill", emptyList()))
        coEvery { playlistDao.getPlaylistIdByUuid("uuid-nueva") } returns null
        coEvery { playlistDao.upsertPlaylist(any()) } returns 42L

        val summary = (repository.import() as AppResult.Success).data

        assertEquals(1, summary.playlistsCreated)
        assertEquals(0, summary.playlistsMerged)
        coVerify { playlistDao.upsertPlaylist(match { it.uuid == "uuid-nueva" && it.name == "Chill" }) }
    }

    @Test
    fun `import resuelve por titulo y artista cuando el id ya no existe`() = runTest {
        // La canción se re-subió a OneDrive con otro item.id, pero conserva sus tags.
        givenRemoteBackup(BackupPlaylist("uuid-1", "Rock", listOf(backupSong("onedrive:id-viejo"))))
        coEvery { playlistDao.getPlaylistIdByUuid("uuid-1") } returns 7L
        coEvery { playlistDao.getMaxOrderIndex(7L) } returns null
        coEvery { songDao.existsById("onedrive:id-viejo") } returns false
        coEvery { songDao.findIdByTitleAndArtist("Uno", "Artista") } returns "onedrive:id-nuevo"
        coEvery { playlistDao.isSongInPlaylist(7L, "onedrive:id-nuevo") } returns false

        val summary = (repository.import() as AppResult.Success).data

        assertEquals(1, summary.songsAdded)
        assertEquals(0, summary.songsMissing)
        coVerify { playlistDao.addSongToPlaylist(PlaylistSongCrossRef(7L, "onedrive:id-nuevo", 0)) }
    }

    @Test
    fun `import sin backup en la nube devuelve NotFound, no un error de red`() = runTest {
        val notFound = HttpException(Response.error<Any>(404, "".toResponseBody()))
        coEvery { oneDriveApi.downloadFile(any(), any()) } throws notFound

        val result = repository.import()

        assertTrue((result as AppResult.Error).error is AppError.NotFound)
    }

    @Test
    fun `un 403 por falta de permiso de escritura pide reconectar la cuenta`() = runTest {
        val forbidden = HttpException(Response.error<Any>(403, "".toResponseBody()))
        coEvery { playlistDao.getAllPlaylists() } returns emptyList()
        coEvery { oneDriveApi.uploadFile(any(), any(), any()) } throws forbidden

        val result = repository.export()

        val error = (result as AppResult.Error).error
        assertTrue(error is AppError.Auth)
        assertTrue((error as AppError.Auth).needsRelogin)
    }

    private fun backupSong(id: String) = BackupSong(id, "LOCAL", "Uno", "Artista", "Album")

    private fun givenRemoteBackup(vararg playlists: BackupPlaylist) {
        val json = gson.toJson(PlaylistBackupFile(1, 0L, playlists.toList()))
        coEvery { oneDriveApi.downloadFile(any(), any()) } returns
            json.toResponseBody("application/json".toMediaType())
    }

    private fun RequestBody.readUtf8(): String = Buffer().also { writeTo(it) }.readUtf8()
}
