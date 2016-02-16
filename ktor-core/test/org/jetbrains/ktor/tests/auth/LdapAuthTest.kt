package org.jetbrains.ktor.tests.auth

import org.apache.directory.api.ldap.util.*
import org.apache.directory.server.annotations.*
import org.apache.directory.server.core.integ.*
import org.apache.directory.server.core.integ.IntegrationUtils.*
import org.apache.directory.server.ldap.*
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.auth.*
import org.jetbrains.ktor.auth.ldap.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.routing.*
import org.jetbrains.ktor.testing.*
import org.jetbrains.ktor.tests.*
import org.junit.*
import org.junit.runner.*
import java.net.*
import java.util.*
import javax.naming.directory.*
import javax.naming.ldap.*
import kotlin.test.*

@RunWith(FrameworkRunner::class)
@CreateLdapServer(
        transports = arrayOf(
                CreateTransport(protocol = "LDAP")
        ))
class LdapAuthTest {
    @Before
    fun setUp() {
        // notice: it is just a test: never keep user password but message digest or hash with salt
        apply(ldapServer.directoryService, getUserAddLdif("uid=user-test,ou=users,ou=system", "test".toByteArray(), "Test user", "test"))
    }

    @Test
    fun testLoginToServer() {
        withTestApplication {
            application.routing {
                auth {
                    basicAuth()
                    verifyWithLdapLoginWithUser("ldap://$localhost:${ldapServer.port}", "uid=%s,ou=system")
                }
                get("/") {
                    response.sendText((authContext.foundPrincipals.singleOrNull() as? UserIdPrincipal)?.name ?: "null")
                }
            }

            handleRequest(HttpMethod.Get, "/", { addHeader(HttpHeaders.Authorization, "Basic " + Base64.getEncoder().encodeToString("admin:secret".toByteArray())) }).let { result ->
                assertEquals(ApplicationCallResult.Handled, result.requestResult)
                assertEquals("admin", result.response.content)
            }
            handleRequest(HttpMethod.Get, "/", { addHeader(HttpHeaders.Authorization, "Basic " + Base64.getEncoder().encodeToString("admin:bad-pass".toByteArray())) }).let { result ->
                assertEquals(ApplicationCallResult.Handled, result.requestResult)
                assertEquals("null", result.response.content)
            }
            handleRequest(HttpMethod.Get, "/", { addHeader(HttpHeaders.Authorization, "Basic " + Base64.getEncoder().encodeToString("bad-user:bad-pass".toByteArray())) }).let { result ->
                assertEquals(ApplicationCallResult.Handled, result.requestResult)
                assertEquals("null", result.response.content)
            }
        }
    }

    @Test
    fun testCustomLogin() {
        withTestApplication {
            application.routing {
                auth {
                    basicAuth()
                    verifyWithLdap("ldap://$localhost:${ldapServer.port}", ldapLoginConfigurator = { c, env ->
                        env.put("java.naming.security.principal", "uid=admin,ou=system")
                        env.put("java.naming.security.credentials", "secret")
                        env.put("java.naming.security.authentication", "simple")
                    }, verifyBlock = { up: UserPasswordCredential ->
                        val users = (lookup("ou=system") as LdapContext).lookup("ou=users") as LdapContext
                        val controls = SearchControls().apply {
                            searchScope = SearchControls.ONELEVEL_SCOPE
                            returningAttributes = arrayOf("+", "*")
                        }
                        val results = users.search("", "(uid=user-test)", controls).toList()

                        if (results.any { (it.attributes.get("userPassword")?.get() as ByteArray?)?.toString(Charsets.ISO_8859_1) == up.password }) {
                            UserIdPrincipal(up.name)
                        } else {
                            null
                        }
                    })
                }

                get("/") {
                    response.sendText((authContext.foundPrincipals.singleOrNull() as? UserIdPrincipal)?.name ?: "null")
                }
            }

            handleRequest(HttpMethod.Get, "/", { addHeader(HttpHeaders.Authorization, "Basic " + Base64.getEncoder().encodeToString("user-test:test".toByteArray())) }).let { result ->
                assertEquals(ApplicationCallResult.Handled, result.requestResult)
                assertEquals("user-test", result.response.content)
            }
            handleRequest(HttpMethod.Get, "/", { addHeader(HttpHeaders.Authorization, "Basic " + Base64.getEncoder().encodeToString("user-test:bad-pass".toByteArray())) }).let { result ->
                assertEquals(ApplicationCallResult.Handled, result.requestResult)
                assertEquals("null", result.response.content)
            }
            handleRequest(HttpMethod.Get, "/", { addHeader(HttpHeaders.Authorization, "Basic " + Base64.getEncoder().encodeToString("bad-user:bad-pass".toByteArray())) }).let { result ->
                assertEquals(ApplicationCallResult.Handled, result.requestResult)
                assertEquals("null", result.response.content)
            }
        }
    }

    @Test
    fun testEnsureUser() {
        val env = Hashtable<String, String>()
        env.put("java.naming.factory.initial", "com.sun.jndi.ldap.LdapCtxFactory")
        env.put("java.naming.provider.url", "ldap://$localhost:${ldapServer.port}")
        env.put("java.naming.security.principal", "uid=admin,ou=system")
        env.put("java.naming.security.credentials", "secret")
        env.put("java.naming.security.authentication", "simple")

        val ctx = (InitialLdapContext(env, JndiUtils.toJndiControls(ldapServer.directoryService.ldapCodecService)).lookup("ou=system") as LdapContext).lookup("ou=users") as LdapContext

        val controls = SearchControls()
        controls.searchScope = SearchControls.ONELEVEL_SCOPE
        controls.returningAttributes = arrayOf("+", "*")
        val res = ctx.search("", "(ObjectClass=*)", controls).toList()

        assertEquals(listOf("user-test"), res.map { it.attributes.get("uid").get().toString() })
    }

    private val localhost: String
        get() =
            try {
                InetAddress.getLocalHost().hostAddress
            } catch (any: Throwable) {
                "127.0.0.1"
            }

    companion object {
        @JvmStatic
        lateinit var ldapServer: LdapServer
    }
}
