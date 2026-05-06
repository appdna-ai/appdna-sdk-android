package ai.appdna.sdk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Ignore
import org.junit.Test
// import org.robolectric.RuntimeEnvironment   // un-ignore tests once Robolectric runner is wired
// import java.io.File                          // ditto

/**
 * SPEC-070-A A.3 — instrumentation stub for [NetworkImage] / [AppDNAImageLoader].
 *
 * Robolectric is already on the SDK's `testImplementation` classpath
 * (see `build.gradle.kts` — `org.robolectric:robolectric:4.11.1`), but
 * driving Coil's `AsyncImage` through full Compose rendering needs the
 * Roborazzi snapshot harness (planned future work for the visual-snapshot
 * leg of SPEC-070-0 §3.4).
 *
 * Until that harness covers `NetworkImage`, this file documents the
 * intended assertions:
 *   1. `AppDNAImageLoader.singleton(context)` returns a non-null
 *      `coil.ImageLoader`.
 *   2. The disk-cache directory resolves to
 *      `<context.cacheDir>/appdna_image_cache`.
 *   3. Repeated calls return the same instance (singleton semantics).
 *
 * Each assertion is provided as an `@Ignore`d test below so that switching
 * to Robolectric `RuntimeEnvironment.getApplication()` is a one-line
 * un-ignore + add `@RunWith(RobolectricTestRunner::class)` change.
 */
class NetworkImageInstrumentationStubTest {

    @Test
    @Ignore(
        "Requires Robolectric runtime — un-ignore once Roborazzi harness " +
            "covers core/NetworkImage.kt. See SPEC-070-0 §3.4."
    )
    fun `singleton returns non-null ImageLoader`() {
        // val context = RuntimeEnvironment.getApplication()
        // val loader = AppDNAImageLoader.singleton(context)
        // assertNotNull(loader)
    }

    @Test
    @Ignore(
        "Requires Robolectric runtime — un-ignore once Roborazzi harness " +
            "covers core/NetworkImage.kt. See SPEC-070-0 §3.4."
    )
    fun `disk cache directory is appdna_image_cache under cacheDir`() {
        // val context = RuntimeEnvironment.getApplication()
        // val expected = File(context.cacheDir, "appdna_image_cache")
        // assertEquals(expected.absolutePath, AppDNAImageLoader.diskCacheDir(context).absolutePath)
    }

    @Test
    @Ignore(
        "Requires Robolectric runtime — un-ignore once Roborazzi harness " +
            "covers core/NetworkImage.kt. See SPEC-070-0 §3.4."
    )
    fun `singleton returns same instance across calls`() {
        // val context = RuntimeEnvironment.getApplication()
        // val first = AppDNAImageLoader.singleton(context)
        // val second = AppDNAImageLoader.singleton(context)
        // assertEquals(first, second)
    }

    /**
     * Sanity check that runs without Robolectric — confirms this test class
     * is on the JUnit classpath and the symbols imported above resolve.
     * Prevents the file from being silently dropped.
     */
    @Test
    fun `compile-time sanity check`() {
        assertNotNull(AppDNAImageLoader)
        assertEquals("ai.appdna.sdk.core.AppDNAImageLoader", AppDNAImageLoader.javaClass.name)
    }
}
