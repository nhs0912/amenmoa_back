package com.moa.amenmoa.api.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service

// 토큰이 들어 있는 응답은 객체를 로그에 찍어도 원문이 나오지 않도록 data class를 사용하지 않습니다.
class TokenResponse(val accessToken: String, val refreshToken: String, val expiresIn: Long) {
    val tokenType = "Bearer"
}

class InvalidRefreshTokenException : RuntimeException()

@Service
class AuthTokens(
    private val encoder: JwtEncoder,
    private val properties: AuthProperties,
    private val redis: StringRedisTemplate,
) {
    private val random = SecureRandom()

    fun start(userId: Long): TokenResponse {
        val sessionId = UUID.randomUUID()
        val refreshToken = newRefreshToken(sessionId)
        val tokens = issueTokens(userId, sessionId, refreshToken)

        // 로그인 한 번당 키 하나를 만들고, Redis TTL로 세션이 자동 만료되게 합니다.
        redis.opsForValue().set(sessionKey(sessionId), sessionValue(userId, refreshToken), REFRESH_TTL)
        return tokens
    }

    fun refresh(refreshToken: String): TokenResponse {
        val sessionId = parseSessionId(refreshToken)
        val key = sessionKey(sessionId)
        val existing = redis.opsForValue().get(key) ?: throw InvalidRefreshTokenException()
        val userId = existing.substringBefore(':').toLong()
        val expected = sessionValue(userId, refreshToken)
        if (!MessageDigest.isEqual(existing.toByteArray(), expected.toByteArray())) {
            throw InvalidRefreshTokenException()
        }

        val replacement = newRefreshToken(sessionId)
        val tokens = issueTokens(userId, sessionId, replacement)
        // Redis가 비교와 저장을 한 번에 실행하므로 동시에 갱신해도 한 요청만 성공합니다.
        val updated = redis.execute(ROTATE, listOf(key), expected, sessionValue(userId, replacement))
        if (updated != true) throw InvalidRefreshTokenException()
        return tokens
    }

    fun end(sessionId: UUID) {
        redis.delete(sessionKey(sessionId))
    }

    private fun issueTokens(userId: Long, sessionId: UUID, refreshToken: String): TokenResponse {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .audience(listOf(properties.audience))
            .subject(userId.toString())
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(now.plus(ACCESS_TTL))
            .claim("sid", sessionId.toString())
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val jwt = encoder.encode(JwtEncoderParameters.from(header, claims))
        return TokenResponse(jwt.tokenValue, refreshToken, ACCESS_TTL.seconds)
    }

    // Access token은 JWT이고 refresh token은 서버가 발급한 불투명 토큰입니다.
    // 세션 ID로 Redis 키를 찾고, 토큰 원문 대신 해시로 소유 여부를 확인합니다.
    private fun newRefreshToken(sessionId: UUID): String {
        val secret = ByteArray(32).also(random::nextBytes)
        return "$sessionId.${BASE64_URL.encodeToString(secret)}"
    }

    private fun parseSessionId(token: String): UUID {
        if (token.length != 80 || token[36] != '.' || !TOKEN_SECRET.matches(token.substring(37))) {
            throw InvalidRefreshTokenException()
        }
        val rawId = token.substring(0, 36)
        val sessionId = runCatching { UUID.fromString(rawId) }.getOrNull()
        if (sessionId == null || sessionId.toString() != rawId) throw InvalidRefreshTokenException()
        return sessionId
    }

    private fun sessionKey(sessionId: UUID) = "amenmoa:auth:session:$sessionId"

    private fun sessionValue(userId: Long, refreshToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray(Charsets.UTF_8))
        return "$userId:${BASE64_URL.encodeToString(digest)}"
    }

    private companion object {
        val ACCESS_TTL: Duration = Duration.ofMinutes(15)
        val REFRESH_TTL: Duration = Duration.ofDays(7)
        val BASE64_URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val TOKEN_SECRET = Regex("[A-Za-z0-9_-]{43}")
        val ROTATE: RedisScript<Boolean> = RedisScript.of(
            """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'KEEPTTL')
            return 1
            """.trimIndent(),
            Boolean::class.javaObjectType,
        )
    }
}
