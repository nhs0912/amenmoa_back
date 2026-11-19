package com.moa.amenmoa.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("local")
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["amenmoa.auth.secret=integration-test-secret-32-bytes-or-longer"],
)
class AuthIntegrationTests {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var json: JsonMapper

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private val client = HttpClient.newHttpClient()

	@Test
	fun `가입에서 로그아웃까지 MySQL과 Redis를 거쳐 처리한다`() {
		val email = "sam@example.com"
		val nickname = "기도하는모아"
		val password = "sample-password-2026"
		val signup = mapOf("email" to email, "nickname" to nickname, "password" to password)

		assertTrue(isAvailable("email", email))
		assertTrue(isAvailable("nickname", nickname))

		val created = request("POST", "/auth/signup", 201, signup).json()
		assertEquals(email, created.path("email").stringValue())
		assertEquals(nickname, created.path("nickname").stringValue())
		assertFalse(created.has("password"))
		assertFalse(created.has("passwordHash"))
		val storedHash = jdbc.queryForObject(
			"SELECT password_hash FROM accounts WHERE email = ?",
			String::class.java,
			email,
		)
		assertTrue(!storedHash.isNullOrBlank() && storedHash != password)
		assertFalse(isAvailable("email", email))
		assertFalse(isAvailable("nickname", nickname))
		request("POST", "/auth/signup", 409, signup + ("nickname" to "다른닉네임"))
		request("POST", "/auth/signup", 409, signup + ("email" to "other@example.com"))

		request("GET", "/me", 401)
		request("POST", "/auth/login", 401, mapOf("email" to email, "password" to "wrong-password-2026"))
		val login = request("POST", "/auth/login", 200, mapOf("email" to email, "password" to password)).json()
		val accessToken = login.path("accessToken").stringValue()
		val refreshToken = login.path("refreshToken").stringValue()
		assertTrue(accessToken.isNotBlank())
		assertTrue(refreshToken.isNotBlank())
		assertEquals(created.path("id"), request("GET", "/me", 200, token = accessToken).json().path("id"))
		request("GET", "/me", 401, token = refreshToken)

		val refreshed = request("POST", "/auth/refresh", 200, mapOf("refreshToken" to refreshToken)).json()
		val nextAccessToken = refreshed.path("accessToken").stringValue()
		val nextRefreshToken = refreshed.path("refreshToken").stringValue()
		assertNotEquals(refreshToken, nextRefreshToken)
		request("GET", "/me", 200, token = nextAccessToken)
		request("POST", "/auth/refresh", 401, mapOf("refreshToken" to refreshToken))
		request("POST", "/auth/logout", 204, token = nextAccessToken)
		request("POST", "/auth/refresh", 401, mapOf("refreshToken" to nextRefreshToken))
	}

	private fun isAvailable(field: String, value: String): Boolean =
		request("POST", if (field == "email") "/auth/email-availability" else "/users/nickname-availability",
			200, mapOf(field to value)).json().path("available").asBoolean()

	private fun request(
		method: String,
		path: String,
		expectedStatus: Int,
		body: Map<String, String>? = null,
		token: String? = null,
	): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1$path"))
		val publisher = if (body == null) {
			HttpRequest.BodyPublishers.noBody()
		} else {
			builder.header("Content-Type", "application/json")
			HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))
		}
		if (token != null) builder.header("Authorization", "Bearer $token")
		val response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
		assertEquals(expectedStatus, response.statusCode(), "$method $path")
		return response
	}

	private fun HttpResponse<String>.json(): JsonNode = json.readTree(body())

	companion object {
		@JvmField
		@Container
		@ServiceConnection
		val mysql = MySQLContainer(DockerImageName.parse("mysql:8.4"))

		@JvmField
		@Container
		@ServiceConnection(name = "redis")
		val redis = RedisContainer().withExposedPorts(6379)
	}

	class RedisContainer : GenericContainer<RedisContainer>(DockerImageName.parse("redis:7.4-alpine"))
}
