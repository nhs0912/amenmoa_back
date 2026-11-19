package com.moa.amenmoa.api.auth

import jakarta.validation.Validator
import jakarta.validation.constraints.Email
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer
import java.util.Locale

enum class AccountFailure {
    INVALID_EMAIL,
    INVALID_NICKNAME,
    INVALID_PASSWORD,
    EMAIL_TAKEN,
    NICKNAME_TAKEN,
    INVALID_CREDENTIALS,
}

class AccountException(val failure: AccountFailure) : RuntimeException(failure.name)

@Service
class AccountService(
    private val accounts: AccountRepository,
    private val passwordEncoder: PasswordEncoder,
    private val validator: Validator,
) {
    // 존재하지 않는 계정도 BCrypt 비교를 한 번 수행해서 응답 시간 차이를 줄인다.
    private val unknownAccountHash = encodePassword("unused-account-password")

    fun isEmailAvailable(email: String): Boolean = !accounts.existsByEmail(normalizeEmail(email))

    fun isNicknameAvailable(nickname: String): Boolean =
        !accounts.existsByNicknameKey(normalizeNickname(nickname).key)

    @Transactional
    fun register(email: String, nickname: String, password: String): Account {
        val normalizedEmail = normalizeEmail(email)
        val normalizedNickname = normalizeNickname(nickname)
        if (!hasValidPasswordLength(password) || password.codePointCount(0, password.length) < 12) {
            throw AccountException(AccountFailure.INVALID_PASSWORD)
        }

        if (accounts.existsByEmail(normalizedEmail)) throw AccountException(AccountFailure.EMAIL_TAKEN)
        if (accounts.existsByNicknameKey(normalizedNickname.key)) {
            throw AccountException(AccountFailure.NICKNAME_TAKEN)
        }

        val account = Account(
            email = normalizedEmail,
            nickname = normalizedNickname.display,
            nicknameKey = normalizedNickname.key,
            passwordHash = encodePassword(password),
        )

        // 사전 조회 뒤 다른 요청이 가입할 수 있다. UNIQUE 제약이 마지막으로 중복을 막는다.
        // flush로 INSERT를 지금 실행하므로 트랜잭션 종료 전에 중복 응답으로 바꿀 수 있다.
        try {
            return accounts.saveAndFlush(account)
        } catch (exception: DataIntegrityViolationException) {
            val constraint = generateSequence(exception.cause) { it.cause }
                .filterIsInstance<ConstraintViolationException>()
                .firstOrNull()
                ?.constraintName
                ?.substringAfterLast('.')
            val failure = when (constraint) {
                "uk_auth_sample_users_email" -> AccountFailure.EMAIL_TAKEN
                "uk_auth_sample_users_nickname" -> AccountFailure.NICKNAME_TAKEN
                else -> throw exception
            }
            throw AccountException(failure)
        }
    }

    fun authenticate(email: String, password: String): Account {
        val normalizedEmail = try {
            normalizeEmail(email)
        } catch (_: AccountException) {
            throw AccountException(AccountFailure.INVALID_CREDENTIALS)
        }
        if (!hasValidPasswordLength(password)) throw AccountException(AccountFailure.INVALID_CREDENTIALS)

        val account = accounts.findByEmail(normalizedEmail)
        val matches = passwordEncoder.matches(password, account?.passwordHash ?: unknownAccountHash)
        if (!matches || account == null) throw AccountException(AccountFailure.INVALID_CREDENTIALS)
        return account
    }

    fun findById(id: Long): Account =
        accounts.findById(id).orElseThrow { AccountException(AccountFailure.INVALID_CREDENTIALS) }

    private fun normalizeEmail(input: String): String {
        if (input.length > 512) throw AccountException(AccountFailure.INVALID_EMAIL)
        // 아멘모아는 이메일 전체의 대소문자를 구분하지 않는다.
        val email = input.trim().lowercase(Locale.ROOT)
        if (email.isEmpty() || email.length > 254 || validator.validate(EmailAddress(email)).isNotEmpty()) {
            throw AccountException(AccountFailure.INVALID_EMAIL)
        }
        return email
    }

    private fun normalizeNickname(input: String): NormalizedNickname {
        if (input.length > 255) throw AccountException(AccountFailure.INVALID_NICKNAME)
        val display = Normalizer.normalize(input.trim(), Normalizer.Form.NFC)
        val key = Normalizer.normalize(display.lowercase(Locale.ROOT), Normalizer.Form.NFC)
        if (
            display.length > 255 || key.length > 255 || hiddenNicknameCharacters.containsMatchIn(display) ||
            grapheme.findAll(display).count() !in 2..20
        ) {
            throw AccountException(AccountFailure.INVALID_NICKNAME)
        }
        return NormalizedNickname(display, key)
    }

    private fun encodePassword(password: String): String =
        checkNotNull(passwordEncoder.encode(password)) { "Password encoder returned no hash" }

    private fun hasValidPasswordLength(password: String): Boolean =
        // BCrypt 입력 제한은 글자 수가 아닌 UTF-8 바이트 수다. 비밀번호 공백은 그대로 둔다.
        password.length in 1..72 && password.toByteArray(Charsets.UTF_8).size <= 72 && !password.isBlank()

    private data class EmailAddress(@field:Email val value: String)

    private data class NormalizedNickname(val display: String, val key: String)

    private companion object {
        val grapheme = Regex("\\X")
        val hiddenNicknameCharacters = Regex("[\\x{0000}-\\x{001F}\\x{007F}-\\x{009F}\\x{200B}\\x{200E}-\\x{200F}\\x{202A}-\\x{202E}\\x{2060}-\\x{206F}\\x{FEFF}]")
    }
}
