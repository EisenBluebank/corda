package net.corda.node.services.keys

import net.corda.core.crypto.*
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.services.IdentityService
import net.corda.core.node.services.KeyManagementService
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.utilities.parsePublicKeyBase58
import net.corda.core.utilities.toBase58String
import net.corda.node.utilities.AppendOnlyPersistentMap
import net.corda.node.utilities.NODE_DATABASE_PREFIX
import org.bouncycastle.operator.ContentSigner
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.Lob

/**
 * A persistent re-implementation of [E2ETestKeyManagementService] to support node re-start.
 *
 * This is not the long-term implementation.  See the list of items in the above class.
 *
 * This class needs database transactions to be in-flight during method calls and init.
 */
class PersistentKeyManagementService(val identityService: IdentityService,
                                     initialKeys: Set<KeyPair>) : SingletonSerializeAsToken(), KeyManagementService {

    @Entity
    @javax.persistence.Table(name = "${NODE_DATABASE_PREFIX}our_key_pairs")
    class PersistentKey(

            @Id
            @Column(length = 6000, name = "public_key")
            var publicKey: String = "",

            @Lob
            @Column(name = "private_key")
            var privateKey: ByteArray = ByteArray(0)
    )

    private companion object {
        fun createKeyMap(): AppendOnlyPersistentMap<PublicKey, PrivateKey, PersistentKey, String> {
            return AppendOnlyPersistentMap(
                    toPersistentEntityKey = { it.toBase58String() },
                    fromPersistentEntity = {
                        Pair(parsePublicKeyBase58(it.publicKey),
                                it.privateKey.deserialize<PrivateKey>(context = SerializationDefaults.STORAGE_CONTEXT))
                    },
                    toPersistentEntity = { key: PublicKey, value: PrivateKey ->
                        PersistentKey().apply {
                            publicKey = key.toBase58String()
                            privateKey = value.serialize(context = SerializationDefaults.STORAGE_CONTEXT).bytes
                        }
                    },
                    persistentEntityClass = PersistentKey::class.java
            )
        }
    }

    val keysMap = createKeyMap()

    init {
        initialKeys.forEach({ it -> keysMap.addWithDuplicatesAllowed(it.public, it.private) })
    }

    override val keys: Set<PublicKey> get() = keysMap.allPersisted().map { it.first }.toSet()

    override fun filterMyKeys(candidateKeys: Iterable<PublicKey>): Iterable<PublicKey> =
            candidateKeys.filter { keysMap[it] != null }

    override fun freshKey(): PublicKey {
        val keyPair = generateKeyPair()
        keysMap[keyPair.public] = keyPair.private
        return keyPair.public
    }

    override fun freshKeyAndCert(identity: PartyAndCertificate, revocationEnabled: Boolean): PartyAndCertificate =
            freshCertificate(identityService, freshKey(), identity, getSigner(identity.owningKey), revocationEnabled)

    private fun getSigner(publicKey: PublicKey): ContentSigner = getSigner(getSigningKeyPair(publicKey))

    //It looks for the PublicKey in the (potentially) CompositeKey that is ours, and then returns the associated PrivateKey to use in signing
    private fun getSigningKeyPair(publicKey: PublicKey): KeyPair {
        val pk = publicKey.keys.first { keysMap[it] != null } //TODO here for us to re-write this using an actual query if publicKey.keys.size > 1
        return KeyPair(pk, keysMap[pk]!!)
    }

    override fun sign(bytes: ByteArray, publicKey: PublicKey): DigitalSignature.WithKey {
        val keyPair = getSigningKeyPair(publicKey)
        return keyPair.sign(bytes)
    }

    // TODO: A full KeyManagementService implementation needs to record activity to the Audit Service and to limit
    //      signing to appropriately authorised contexts and initiating users.
    override fun sign(signableData: SignableData, publicKey: PublicKey): TransactionSignature {
        val keyPair = getSigningKeyPair(publicKey)
        return keyPair.sign(signableData)
    }
}
