package org.telegram.tgnet.test

import com.appmattus.kotlinfixture.config.Configuration
import com.appmattus.kotlinfixture.config.ConfigurationBuilder
import org.junit.Test
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.model.generated.TlGen_EmojiStatus
import org.telegram.tgnet.model.generated.TlGen_MessageEntity
import org.telegram.tgnet.model.generated.TlGen_PeerColor
import org.telegram.tgnet.model.generated.TlGen_auth_Authorization
import kotlin.reflect.KClass

class NativeSchemeTest : BaseSchemeTest() {
    @Test
    fun test_authReq() {
        test_TLdeserializeNative(TlGen_auth_Authorization.TL_auth_authorizationSignUpRequired::class, ConnectionsManager::native_test_AuthAuthorization) { b ->
            b.factory<TlGen_MessageEntity> {
                createConcreteMessageEntity()
            }
        }
    }

    @Test
    fun test_auth() {
        val configuration = ConfigurationBuilder(fixture.fixtureConfiguration).apply {
            filter<TlGen_PeerColor> { filter { it !is TlGen_PeerColor.TL_inputPeerColorCollectible } }
            filter<TlGen_EmojiStatus> { filter { it !is TlGen_EmojiStatus.TL_inputEmojiStatusCollectible } }
        }.build()

        test_TLdeserializeNative(TlGen_auth_Authorization.TL_auth_authorization::class, ConnectionsManager::native_test_AuthAuthorization) { b ->
            b.factory<TlGen_EmojiStatus> {
                createAllowedSubclass(TlGen_EmojiStatus::class, configuration) {
                    it !is TlGen_EmojiStatus.TL_inputEmojiStatusCollectible
                }
            }
            b.factory<TlGen_PeerColor> {
                createAllowedSubclass(TlGen_PeerColor::class, configuration) {
                    it !is TlGen_PeerColor.TL_inputPeerColorCollectible
                }
            }
        }
    }

    private fun <T : Any> createAllowedSubclass(
        baseClass: KClass<T>,
        configuration: Configuration,
        predicate: (T) -> Boolean = { true }
    ): T {
        var lastError: Throwable? = null

        for (subclass in baseClass.sealedSubclasses.shuffled()) {
            try {
                @Suppress("DEPRECATION_ERROR", "UNCHECKED_CAST")
                val candidate = fixture.create(subclass, configuration) as T
                if (predicate(candidate)) {
                    return candidate
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }

        throw UnsupportedOperationException("Unable to create allowed subclass for ${baseClass.qualifiedName}", lastError)
    }

    private fun createConcreteMessageEntity(): TlGen_MessageEntity {
        val candidates = listOf<KClass<out TlGen_MessageEntity>>(
            TlGen_MessageEntity.TL_messageEntityTextUrl::class,
            TlGen_MessageEntity.TL_messageEntityBotCommand::class,
            TlGen_MessageEntity.TL_messageEntityEmail::class,
            TlGen_MessageEntity.TL_messageEntityPre::class,
            TlGen_MessageEntity.TL_messageEntityUnknown::class,
            TlGen_MessageEntity.TL_messageEntityUrl::class,
            TlGen_MessageEntity.TL_messageEntityItalic::class,
            TlGen_MessageEntity.TL_messageEntityMention::class,
            TlGen_MessageEntity.TL_messageEntityCashtag::class,
            TlGen_MessageEntity.TL_messageEntityBold::class,
            TlGen_MessageEntity.TL_messageEntityHashtag::class,
            TlGen_MessageEntity.TL_messageEntityCode::class,
            TlGen_MessageEntity.TL_messageEntityStrike::class,
            TlGen_MessageEntity.TL_messageEntityBlockquote::class,
            TlGen_MessageEntity.TL_messageEntityUnderline::class,
            TlGen_MessageEntity.TL_messageEntityPhone::class
        )

        var lastError: Throwable? = null
        for (subclass in candidates.shuffled()) {
            try {
                @Suppress("DEPRECATION_ERROR")
                return fixture.create(subclass, fixture.fixtureConfiguration) as TlGen_MessageEntity
            } catch (t: Throwable) {
                lastError = t
            }
        }

        throw UnsupportedOperationException("Unable to create concrete message entity", lastError)
    }
}
