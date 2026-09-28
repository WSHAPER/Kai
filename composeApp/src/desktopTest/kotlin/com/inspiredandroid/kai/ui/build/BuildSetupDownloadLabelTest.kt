package com.inspiredandroid.kai.ui.build

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Regression test for the "Downloading Debian… 42%%" double-percent render on
 * the Kai Build setup screen.
 *
 * Desktop tests cannot load compose resources at runtime (nothing wires
 * composeResources into the desktopTest source set), so this test reads the
 * kai_build_step_download template from every composeResources strings.xml
 * on disk (the base values folder and each values-XX locale folder), formats
 * it the way Compose Multiplatform 1.12.1 does, and then applies the
 * production seam [collapsePercentEscape] that the BuildStep.Download branch
 * of BuildSetupContent.kt applies. A locale fails if the rendered label does
 * not show "42" followed by exactly one percent sign.
 *
 * The convention guard additionally pins every template to the "%%" escape: a
 * bare "%" in the XML would render fine on today's CMP but crash the moment a
 * future formatter delegates to java.util.Formatter (String.format rejects a
 * trailing lone percent). Do not "simplify" the resources or remove the
 * collapse call; this file exists to block both.
 */
class BuildSetupDownloadLabelTest {

    @Test
    fun `download label renders the percent argument followed by a single percent sign in every locale`() {
        val failures = mutableListOf<String>()
        for ((locale, template) in localeTemplates()) {
            val rendered = cmpReplaceWithArgs(template, listOf(ARG)).collapsePercentEscape()
            if ("$ARG%" !in rendered || "%%" in rendered || !rendered.endsWith("%")) {
                failures += "$locale: template=$template rendered=$rendered"
            }
        }
        assertTrue(failures.isEmpty(), "locales with a broken download label: $failures")
    }

    @Test
    fun `every locale template keeps the double percent escape`() {
        val offenders = localeTemplates().filterValues { "%%" !in it }.keys
        assertTrue(offenders.isEmpty(), "templates missing the %% escape (a future String.format-based formatter would crash on a bare percent): $offenders")
    }

    @Test
    fun `collapse percent escape follows replace double percent with single percent semantics`() {
        assertEquals("", "".collapsePercentEscape())
        assertEquals("42%", "42%%".collapsePercentEscape())
        assertEquals("a%b%c", "a%%b%%c".collapsePercentEscape())
        assertEquals("no escapes", "no escapes".collapsePercentEscape())
    }

    /**
     * Replicates Compose Multiplatform 1.12.1's string resource formatter
     * (components-resources StringResourcesUtils.kt replaceWithArgs): only
     * %N$d / %N$s tokens matched by %(\d+)\$[ds] are substituted with the
     * argument at position N-1. Every other character, including the "%%"
     * escape, passes through verbatim; java.util.Formatter would unescape it.
     */
    private fun cmpReplaceWithArgs(template: String, args: List<String>): String =
        CMP_TOKEN.replace(template) { match -> args[match.groupValues[1].toInt() - 1] }

    /**
     * Extracts the raw text of the [KEY] string entry from a strings.xml file,
     * resolving XML entities the same way a resource loader would.
     */
    private fun extractTemplate(xml: File): String? {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val nodes = root.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.getAttribute("name") == KEY) return node.textContent
        }
        return null
    }

    /**
     * Collects [KEY] from the strings.xml of every composeResources values
     * folder (base and locale), keyed by folder name. The repo root is located by walking up from the working
     * directory so the test works whether Gradle runs it from the repo root or
     * the composeApp module directory.
     */
    private fun localeTemplates(): Map<String, String> {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, RES_DIR).isDirectory) dir = dir.parentFile
        assertTrue(dir != null, "could not locate $RES_DIR above ${System.getProperty("user.dir")}")
        val templates = File(dir, RES_DIR)
            .listFiles { folder -> folder.isDirectory && folder.name.startsWith("values") }
            .orEmpty()
            .filter { folder -> File(folder, "strings.xml").isFile }
            .sortedBy { folder -> folder.name }
            .mapNotNull { folder -> extractTemplate(File(folder, "strings.xml"))?.let { folder.name to it } }
            .toMap()
        assertTrue("values" in templates, "base locale is missing its $KEY entry")
        assertTrue(templates.size > 1, "expected more than just the base locale under composeResources")
        return templates
    }

    private companion object {
        const val KEY = "kai_build_step_download"
        const val ARG = "42"
        const val RES_DIR = "composeApp/src/commonMain/composeResources"
        val CMP_TOKEN = Regex("%(\\d+)\\$[ds]")
    }
}
