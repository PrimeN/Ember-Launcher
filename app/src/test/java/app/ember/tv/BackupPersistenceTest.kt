package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.ProfilesState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupPersistenceTest {
    @get:Rule val temp=TemporaryFolder()
    private fun context()=PersistenceContext(temp.newFolder())
    private fun sample(ctx:PersistenceContext,label:String):BackupPersistence {
        val prefs=Prefs(ctx)
        prefs.theme="day";prefs.devicePerformance="low";prefs.artworkQuality="high";prefs.navigationGlass=true
        prefs.textSize="large";prefs.menuPosition="side";prefs.hidden=setOf("org.example.hidden")
        prefs.detailedBadgeCategoriesOff=setOf("ga","gl")
        prefs.nav=listOf(NavTabs.APPS to true,NavTabs.HOME to true,NavTabs.LIVE to false)
        prefs.playRules=PlayRules(maxQuality="FHD",hdr="sdr",serverFirst=false)
        prefs.profiles=listOf(Profile("adult","Adult"),Profile("kids","Kids",kids=true,personal=false))
        prefs.currentProfile="kids"
        ctx.getSharedPreferences("ember",0).edit().putFloat("futureFraction",.375f).putLong("updateCheckedAt",9_007_199_254_740_993L).commit()
        ctx.getSharedPreferences("server_browser",0).edit().putString("library.sort","title").putString("library.density","dense").commit()
        ctx.getSharedPreferences("positions",0).edit().putString("tt1:1:2","123000,3600000").commit()
        ctx.getSharedPreferences("trackMemory",0).edit().putString("a:tt1","ja").putString("s:tt1","off").commit()
        ctx.getSharedPreferences("screensaver",0).edit().putStringSet("sources",setOf("home","art")).putString("photoFolder","Vacation").putLong("collectionsAt",1234).commit()
        File(ctx.filesDir,"collections.json").writeText("[]")
        File(ctx.filesDir,"badges-custom.json").writeText("""{"filters":[{"id":"$label","pattern":".*"}]}""")
        File(ctx.filesDir,"library.json").writeText("""{"plays":[{"key":"tt1","meta":{"id":"tt1","type":"movie","name":"$label"},"progress":0.35}],"trackerWatchlist":[{"id":"tt2","name":"Saved"}],"pending":[{"add":true,"meta":{"id":"tt3"},"revision":"fixture-revision"}]}""")
        return BackupPersistence(ctx,FixtureSecrets(mapOf("apikey.TMDB_API_KEY" to "fixture-key-$label","trakt.access" to "fixture-access","addons.config" to "[]","network.locations" to "[]")))
    }
    @Test fun everyManagedStoreAndAllPreferenceTypesRoundTripWithoutDroppingFutureKeys() {
        val from=context();val source=sample(from,"Source");val payload=source.snapshot()
        val to=context();val target=BackupPersistence(to,FixtureSecrets())
        target.restore(payload)
        assertEquals(payload,target.snapshot())
        assertEquals(setOf("ember","screensaver","positions","trackMemory","server_browser","ember_watched"),BackupFormat.preferenceFiles.toSet())
        val loaded=Prefs(to)
        assertEquals("day",loaded.theme);assertEquals("low",loaded.devicePerformance);assertTrue(loaded.navigationGlass)
        assertEquals("large",loaded.textSize);assertEquals("side",loaded.menuPosition)
        assertEquals(setOf("ga","gl"),loaded.detailedBadgeCategoriesOff)
        assertEquals("FHD",loaded.playRules.maxQuality);assertFalse(loaded.playRules.serverFirst)
        assertEquals("kids",loaded.currentProfile);assertTrue(loaded.profiles.last().kids)
        assertEquals("title",to.getSharedPreferences("server_browser",0).getString("library.sort",null))
        assertEquals(.375f,to.getSharedPreferences("ember",0).getFloat("futureFraction",0f),.0001f)
        assertEquals(9_007_199_254_740_993L,to.getSharedPreferences("ember",0).getLong("updateCheckedAt",0))
    }
    @Test fun plainAndEncryptedBackupsUseTheActualCryptoAndReadBackTheCompletePayload() {
        val payload=sample(context(),"Source").snapshot()
        assertEquals(payload,Backup.read(Backup.encodePayload(payload,""),"").first)
        val encrypted=Backup.encodePayload(payload,"fixture-passphrase")
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("fixture-key-Source"))
        assertEquals(payload,Backup.read(encrypted,"fixture-passphrase").first)
        assertTrue(runCatching {Backup.read(encrypted,"wrong")}.exceptionOrNull() is Backup.BackupException)
        val envelope=Json.parseToJsonElement(encrypted.toString(Charsets.UTF_8)).jsonObject
        val raw=java.util.Base64.getDecoder().decode(envelope.getValue("data").jsonPrimitive.content)
        raw[0]=(raw[0].toInt() xor 1).toByte()
        val tampered=JsonObject(envelope+("data" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(raw))))
        assertTrue(runCatching {Backup.read(tampered.toString().toByteArray(),"fixture-passphrase")}.exceptionOrNull() is Backup.BackupException)
    }
    @Test fun invalidEncryptionParametersAreRejectedBeforeExpensiveDerivation() {
        val payload=sample(context(),"Source").snapshot()
        val envelope=Json.parseToJsonElement(Backup.encodePayload(payload,"test").toString(Charsets.UTF_8)).jsonObject
        val invalid=JsonObject(envelope+("iterations" to JsonPrimitive(Int.MAX_VALUE)))
        val error=runCatching {Backup.read(invalid.toString().toByteArray(),"test")}.exceptionOrNull()
        assertTrue(error is Backup.BackupException);assertTrue(error!!.message!!.contains("parameters"))
    }
    @Test fun legacyBackupWithoutBrowserPreferencesResetsOnlyTheMissingNamespacesToDefaults() {
        val payload=JsonObject(sample(context(),"Source").snapshot()-"prefs.server_browser")
        val targetCtx=context();val target=sample(targetCtx,"Old")
        target.restore(payload)
        assertTrue(targetCtx.getSharedPreferences("server_browser",0).all.isEmpty())
        assertEquals("day",Prefs(targetCtx).theme)
    }
    @Test fun malformedTypedSettingsAndSavedFilesFailBeforeAnyMutation() {
        val payload=sample(context(),"Source").snapshot()
        val ctx=context();val target=sample(ctx,"Old");val before=target.snapshot()
        val prefs=payload.getValue("prefs.ember").jsonObject
        val cases=listOf(
            JsonObject(payload+("prefs.ember" to JsonObject(prefs+("navigationGlass" to buildJsonObject {put("t","s");put("v","true")})))),
            JsonObject(payload+("prefs.ember" to JsonObject(prefs+("playRules" to buildJsonObject {put("t","s");put("v","invalid json")})))),
            JsonObject(payload+("files" to buildJsonObject {put("library.json","invalid json")})),
            JsonObject(payload+("files" to buildJsonObject {put("../escape","{}") })),
            JsonObject(emptyMap()),
        )
        for(bad in cases) {
            assertTrue(runCatching {target.restore(bad)}.exceptionOrNull() is Backup.BackupException)
            assertEquals(before,target.snapshot())
        }
    }
    @Test fun aFailedPreferenceCommitRollsBackItsMutatedMemoryAndEarlierStores() {
        val payload=sample(context(),"Source").snapshot()
        val ctx=context();val target=sample(ctx,"Old");val before=target.snapshot()
        ctx.memory("positions").failures=1
        assertTrue(runCatching {target.restore(payload)}.exceptionOrNull() is Backup.BackupException)
        assertEquals(before,target.snapshot());assertEquals("",ConfigurationGeneration.current(ctx.filesDir))
    }
    @Test fun failedCredentialReplacementRollsBackPrefsTokensAndFiles() {
        val payload=sample(context(),"Source").snapshot()
        val ctx=context();sample(ctx,"Old")
        val secrets=FixtureSecrets(mapOf("old" to "fixture-old"));val target=BackupPersistence(ctx,secrets);val before=target.snapshot()
        secrets.failNext=true
        assertTrue(runCatching {target.restore(payload)}.exceptionOrNull() is Backup.BackupException)
        assertEquals(before,target.snapshot())
    }
    @Test fun fileWriteFailureRollsBackEarlierFilesAndPreferences() {
        val payload=sample(context(),"Source").snapshot()
        val ctx=context();sample(ctx,"Old");val secrets=FixtureSecrets(mapOf("old" to "fixture-old"))
        var fail=true
        val target=BackupPersistence(ctx,secrets){file,bytes->
            if(file.name=="badges-custom.json" && fail){fail=false;error("simulated full disk")}
            if(bytes==null)file.delete() else AtomicFile.write(file,bytes).getOrThrow()
        }
        val before=target.snapshot()
        assertTrue(runCatching {target.restore(payload)}.exceptionOrNull() is Backup.BackupException)
        assertEquals(before,target.snapshot())
    }
    @Test fun silentFileWriteFailureIsDetectedAndPreviousStateIsRecovered() {
        val payload=sample(context(),"Source").snapshot()
        val ctx=context();sample(ctx,"Old");var drop=true
        val target=BackupPersistence(ctx,FixtureSecrets()){file,bytes->
            if(file.name=="library.json" && drop)drop=false
            else if(bytes==null)file.delete() else AtomicFile.write(file,bytes).getOrThrow()
        }
        val before=target.snapshot()
        assertTrue(runCatching {target.restore(payload)}.exceptionOrNull() is Backup.BackupException)
        assertEquals(before,target.snapshot())
    }
    @Test fun anIncompleteRollbackIsReportedInsteadOfClaimingTheOldDataWasKept() {
        val payload=sample(context(),"Source").snapshot()
        val ctx=context();sample(ctx,"Old");var recovering=false
        val target=BackupPersistence(ctx,FixtureSecrets()){file,bytes->
            if(file.name=="library.json" && !recovering){recovering=true;error("simulated failure")}
            if(!(recovering && file.name=="badges-custom.json")) {
                if(bytes==null)file.delete() else AtomicFile.write(file,bytes).getOrThrow()
            }
        }
        val error=runCatching {target.restore(payload)}.exceptionOrNull()
        assertTrue(error is Backup.BackupException)
        assertTrue(error!!.message!!.contains("could not be recovered"))
    }

    @Test fun restoreCannotClearLegacyCredentialsWhenEncryptedRollbackIsUnavailable() {
        val ctx=context();val target=BackupPersistence(ctx,FixtureSecrets(mapOf("old" to "fixture-old"),false));val before=target.snapshot()
        val empty=buildJsonObject {put("prefs.ember",JsonObject(emptyMap()));put("tokens",JsonObject(emptyMap()));put("files",JsonObject(emptyMap()))}
        assertTrue(runCatching {target.restore(empty)}.exceptionOrNull() is Backup.BackupException)
        assertEquals(before,target.snapshot())
    }
    @Test fun savedNavigationSurvivesItsFirstReadAndAnOlderMigrationFlag() {
        val ctx=context();val prefs=Prefs(ctx)
        prefs.nav=listOf(NavTabs.APPS to true,NavTabs.HOME to true,NavTabs.LIVE to false)
        assertEquals(NavTabs.APPS,Prefs(ctx).nav.first().first)
        ctx.getSharedPreferences("ember",0).edit().remove("navDefaults2").commit()
        assertEquals(NavTabs.APPS,Prefs(ctx).nav.first().first)
        assertFalse(Prefs(ctx).nav.single{it.first==NavTabs.LIVE}.second)
    }
    @Test fun aMissingProfileIdIsRepairedToTheProfileActuallyShown() {
        val ctx=context();val prefs=Prefs(ctx)
        prefs.profiles=listOf(Profile("kids","Kids",kids=true,personal=false),Profile("adult","Adult"));prefs.currentProfile="removed"
        val state=ProfilesState(prefs,{emptyList()},{emptyList()},{_,go->go()},{},{})
        assertEquals("kids",state.profileId);assertEquals(state.profile.id,state.profileId)
        assertEquals("kids",Prefs(ctx).currentProfile);assertFalse(state.personalOn)
    }
    @Test fun restoreRejectsLateHomeAndGuideWritesFromThePreviousConfiguration() {
        val ctx=context();val target=BackupPersistence(ctx,FixtureSecrets())
        val old=HomeSnapshot.Data(rows=listOf(HomeSnapshot.Row("A","Old",emptyList())),savedAt=1)
        HomeSnapshot.save(ctx,old)
        target.restore(buildJsonObject {put("prefs.ember",JsonObject(emptyMap()));put("tokens",JsonObject(emptyMap()));put("files",JsonObject(emptyMap()))})
        val generation=ConfigurationGeneration.current(ctx.filesDir);assertTrue(generation.isNotEmpty())
        HomeSnapshot.save(ctx,old);assertNull(HomeSnapshot.load(ctx))
        HomeSnapshot.save(ctx,old.copy(rows=listOf(HomeSnapshot.Row("A","Current",emptyList())),generation=generation))
        HomeSnapshot.save(ctx,old)
        assertEquals("Current",HomeSnapshot.load(ctx)!!.rows.single().title)
        var oldGuideWritten=false
        ConfigurationGeneration.writeIfCurrent(ctx.filesDir,""){oldGuideWritten=true}.getOrThrow()
        assertFalse(oldGuideWritten)
    }
}
