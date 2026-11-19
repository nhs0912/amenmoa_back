package com.moa.amenmoa.api.auth

import org.springframework.data.jpa.repository.JpaRepository

interface AccountRepository : JpaRepository<Account, Long> {
    // Spring Data JPA가 메서드 이름을 보고 쿼리를 생성한다.
    fun existsByEmail(email: String): Boolean
    fun existsByNicknameKey(nicknameKey: String): Boolean
    fun findByEmail(email: String): Account?
}
