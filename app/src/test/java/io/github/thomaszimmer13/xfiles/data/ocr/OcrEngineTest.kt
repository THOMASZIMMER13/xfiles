package io.github.thomaszimmer13.xfiles.data.ocr

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Moteur de reconnaissance avec les vrais modèles PP-OCRv5. Les modèles (13 Mo) ne sont pas dans le dépôt :
 * test lancé seulement si XFILES_OCR_MODELS désigne un dossier contenant det.onnx, rec.onnx et les images.
 */
class OcrEngineTest {
    companion object {
        private val dir = System.getenv("XFILES_OCR_MODELS")?.let(::File)
        private var engine: OcrEngine? = null

        @BeforeClass @JvmStatic fun setUp() {
            if (dir == null || !File(dir, "det.onnx").exists()) return
            val dict = OcrEngine.loadDictionary(File("src/main/assets/ocr/latin_dict.txt").readLines().asSequence())
            engine = OcrEngine(File(dir, "det.onnx"), File(dir, "rec.onnx"), dict)
        }

        @AfterClass @JvmStatic fun tearDown() {
            engine?.close()
        }
    }

    private fun read(name: String): OcrPage {
        val e = engine
        assumeTrue("Modèles OCR absents", e != null)
        val img = ImageIO.read(File(dir, name))
        val pixels = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, pixels, 0, img.width)
        val start = System.currentTimeMillis()
        val page = e!!.recognize(RgbImage(img.width, img.height, pixels))
        println("$name : ${System.currentTimeMillis() - start} ms, quarts de tour ${page.quarterTurns}")
        page.paragraphs.forEach { println("  ¶ $it") }
        return page
    }

    private val expected = listOf(
        "Mairie de Lyon",
        "Objet : rendez-vous pour votre carte d'identité",
        "le mercredi 25 novembre 2026 à 14 h 30, au 1 place de la Comédie",
        "Pièces à fournir : justificatif de domicile, photo d'identité",
        "04 72 10 30 30 ou etat-civil@mairie-lyon.fr",
        "Veuillez agréer nos salutations distinguées",
    )

    private fun check(page: OcrPage) {
        val text = page.text
        expected.forEach { assertTrue("« $it » manquant dans :\n$text", it in text) }
    }

    @Test fun cleanLetter() = check(read("lettre.png"))

    @Test fun tiltedPhoto() = check(read("photo.jpg"))

    @Test fun upsideDown() {
        val page = read("envers.jpg")
        assertEquals(2, page.quarterTurns)
        check(page)
    }

    @Test fun quarterTurn() {
        val page = read("quart.jpg")
        assertTrue(page.quarterTurns == 1 || page.quarterTurns == 3)
        check(page)
    }

    @Test fun paragraphsAndHyphenation() {
        val p = read("paragraphes.png").paragraphs
        assertEquals(
            listOf(
                "Compte rendu",
                "La réunion s'est tenue en présence de toute l'équipe technique. Le déploiement est prévu pour la fin du mois.",
                "Prochaine étape : valider la configuration avec le client avant vendredi.",
            ),
            p,
        )
    }
}
