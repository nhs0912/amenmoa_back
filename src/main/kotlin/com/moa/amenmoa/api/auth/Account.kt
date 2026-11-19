package com.moa.amenmoa.api.auth

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** MySQL에 저장하는 회원 정보. API에서는 필요한 필드만 별도 DTO로 반환한다. */
@Entity
@Table(name = "accounts")
class Account(햣
    @Column(nullable = false, length = 254)
    val email: String,

    @Column(nullable = false, length = 255)
    val nickname: String,

    @Column(name = "nickname_key", nullable = false, length = 255)
    val nicknameKey: String,

    // 응답 DTO를 사용하는 게 기본이고, 실수로 엔티티를 직렬화해도 해시는 숨긴다.
    @get:JsonIgnore
    @Column(name = "password_hash", nullable = false, length = 60)
    val passwordHash: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set
}
