import kotlin.test.Test
import kotlin.test.assertEquals

class MesaRuntimeLockCodecTest {
    @Test
    fun `codec decodes bundle patch set ids`() {
        val raw = """
            {
              "profile": "turnip-dual-mesa",
              "bundles": [
                {
                  "branch": "mesa25",
                  "mesaVersion": "25.3-branch@7f1ccad77883",
                  "sourceKind": "git",
                  "sourceUrl": "https://gitlab.freedesktop.org/mesa/mesa.git",
                  "sourceRef": "25.3",
                  "sourceRevision": "7f1ccad77883be68e7750ab30b99b16df02e679d",
                  "installProfile": "turnip-kgsl-minimal",
                  "patchSetId": "none"
                },
                {
                  "branch": "mesa26",
                  "mesaVersion": "26.x-main@44669146808b",
                  "sourceKind": "git",
                  "sourceUrl": "https://gitlab.freedesktop.org/mesa/mesa.git",
                  "sourceRef": "main",
                  "sourceRevision": "44669146808b74024b9befeb59266db18ae5e165",
                  "installProfile": "turnip-kgsl-minimal",
                  "patchSetId": "ubwc5-a830-v1"
                }
              ]
            }
        """.trimIndent()

        val lock = MesaRuntimeLockCodec.decode(raw)

        assertEquals("none", lock.bundles[0].patchSetId)
        assertEquals("ubwc5-a830-v1", lock.bundles[1].patchSetId)
    }
}
