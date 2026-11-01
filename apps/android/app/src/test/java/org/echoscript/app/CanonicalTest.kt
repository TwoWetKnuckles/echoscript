package org.echoscript.app
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
class CanonicalTest {
    @Test fun canonicalOrderingMatchesVerifier() { assertEquals("{\"a\":1,\"z\":2}", EvidenceVault.canonical(JSONObject().put("z", 2).put("a", 1)).toString(Charsets.UTF_8)) }
    @Test fun sha256KnownVector() { assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", EvidenceVault.sha("abc".toByteArray())) }
}
