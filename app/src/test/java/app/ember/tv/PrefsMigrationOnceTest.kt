package app.ember.tv

import app.ember.tv.data.CatalogIdentity
import app.ember.tv.data.Prefs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #31: the catalog identity migration runs once per store, and again after a restore. */
class PrefsMigrationOnceTest {
    @get:Rule val temp = TemporaryFolder()
    private val legacy = "https://example.com/manifest.json|movie|top"

    @Test fun migratesOnceThenAgainAfterForget() {
        val ctx = PersistenceContext(temp.root)
        val sp = ctx.getSharedPreferences("ember", 0)
        sp.edit().putString("catalogOrder", legacy).commit()
        Prefs(ctx)
        assertEquals(CatalogIdentity.migrate(legacy), sp.getString("catalogOrder", null))
        // Later constructions skip the work.
        sp.edit().putString("catalogOrder", legacy).commit()
        repeat(20) { Prefs(ctx) }
        assertEquals(legacy, sp.getString("catalogOrder", null))
        // A restore forgets the store, so restored values are migrated by the next Prefs.
        CatalogIdentity.forget(sp)
        Prefs(ctx)
        assertEquals(CatalogIdentity.migrate(legacy), sp.getString("catalogOrder", null))
    }
}
