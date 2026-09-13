"""
Compatibility tests for Azure Key Vault Keys and Cryptography.
"""
import os
import time
import uuid
import hashlib

import pytest
from azure.core.credentials import AccessToken, TokenCredential
from azure.core.exceptions import ResourceNotFoundError
from azure.core.pipeline.transport import RequestsTransport
from azure.keyvault.keys.crypto import (
    CryptographyClient,
    EncryptionAlgorithm,
    KeyWrapAlgorithm,
    SignatureAlgorithm,
)


def unique(prefix="key"):
    return f"{prefix}-{uuid.uuid4().hex[:8]}"


# The emulator returns key ids with host `devstoreaccount1.vault.azure.net`, which
# does not resolve back to the emulator. We build a host-based key id (the only shape
# parse_key_vault_id accepts) and have the transport rewrite the request back onto the
# emulator's path-based route: https://devstoreaccount1.vault.azure.net/keys/... becomes
# {endpoint}/devstoreaccount1-keyvault/keys/....
_ENDPOINT = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
_VAULT_HOST = "devstoreaccount1.vault.azure.net"


class _FakeCredential(TokenCredential):
    def get_token(self, *scopes, **kwargs):
        return AccessToken("fake-token-for-local-emulator", int(time.time()) + 3600)


class _ForceHttpTransport(RequestsTransport):
    def send(self, request, **kwargs):
        request.url = request.url.replace(
            f"https://{_VAULT_HOST}/", f"{_ENDPOINT}/devstoreaccount1-keyvault/", 1
        )
        return super().send(request, **kwargs)


def _crypto(key_name, key_version):
    kid = f"https://{_VAULT_HOST}/keys/{key_name}/{key_version}"
    return CryptographyClient(
        kid,
        credential=_FakeCredential(),
        transport=_ForceHttpTransport(),
        verify_challenge_resource=False,
    )


# ---------------------------------------------------------------------------
# Key CRUD + lifecycle
# ---------------------------------------------------------------------------

def test_create_and_get_rsa_key(keys_client):
    name = unique("rsa")
    created = keys_client.create_rsa_key(name, size=2048)
    assert created.name == name
    assert created.key.kty == "RSA"
    assert created.key.n is not None

    fetched = keys_client.get_key(name)
    assert fetched.name == name
    keys_client.begin_delete_key(name).result()


def test_create_and_get_ec_key(keys_client):
    name = unique("ec")
    created = keys_client.create_ec_key(name, curve="P-256")
    assert created.key.x is not None
    assert created.key.y is not None

    assert keys_client.get_key(name).name == name
    keys_client.begin_delete_key(name).result()


def test_create_and_get_oct_key(keys_client):
    name = unique("oct")
    created = keys_client.create_oct_key(name, size=256)
    # Symmetric key material is never released in a Key Vault response.
    assert created.key.k is None

    assert keys_client.get_key(name).name == name
    keys_client.begin_delete_key(name).result()


def test_get_nonexistent_key_raises(keys_client):
    with pytest.raises(ResourceNotFoundError):
        keys_client.get_key(unique("nonexistent"))


def test_list_properties_of_keys(keys_client):
    name = unique("list")
    keys_client.create_rsa_key(name, size=2048)

    listed = [p.name for p in keys_client.list_properties_of_keys()]
    assert name in listed
    keys_client.begin_delete_key(name).result()


def test_list_properties_of_key_versions(keys_client):
    name = unique("ver")
    keys_client.create_rsa_key(name, size=2048)
    keys_client.create_rsa_key(name, size=2048)

    versions = list(keys_client.list_properties_of_key_versions(name))
    assert len(versions) >= 2
    keys_client.begin_delete_key(name).result()


def test_soft_delete_lifecycle(keys_client):
    name = unique("lifecycle")
    keys_client.create_rsa_key(name, size=2048)

    keys_client.begin_delete_key(name).result()
    deleted = keys_client.get_deleted_key(name)
    assert deleted.name == name

    keys_client.begin_recover_deleted_key(name).result()
    assert keys_client.get_key(name).name == name

    keys_client.begin_delete_key(name).result()
    keys_client.purge_deleted_key(name)

    with pytest.raises(ResourceNotFoundError):
        keys_client.get_deleted_key(name)


def test_backup_and_restore_key(keys_client):
    name = unique("backup")
    keys_client.create_rsa_key(name, size=2048)

    backup = keys_client.backup_key(name)
    assert backup is not None
    assert len(backup) > 0

    keys_client.begin_delete_key(name).result()
    keys_client.purge_deleted_key(name)

    restored = keys_client.restore_key_backup(backup)
    assert restored.name == name
    keys_client.begin_delete_key(name).result()


def test_key_rotation(keys_client):
    name = unique("rotate")
    created = keys_client.create_rsa_key(name, size=2048)
    original_version = created.properties.version

    policy = keys_client.get_key_rotation_policy(name)
    assert policy is not None
    assert hasattr(policy, "lifetime_actions")

    rotated = keys_client.rotate_key(name)
    assert rotated.properties.version != original_version
    keys_client.begin_delete_key(name).result()


# ---------------------------------------------------------------------------
# Cryptography
# ---------------------------------------------------------------------------

def test_rsa_oaep_256_encrypt_decrypt(keys_client):
    name = unique("crypt-oaep256")
    key = keys_client.create_rsa_key(name, size=2048)
    crypto = _crypto(name, key.properties.version)

    encrypted = crypto.encrypt(EncryptionAlgorithm.rsa_oaep_256, b"hello")
    decrypted = crypto.decrypt(EncryptionAlgorithm.rsa_oaep_256, encrypted.ciphertext)
    assert decrypted.plaintext == b"hello"
    keys_client.begin_delete_key(name).result()


def test_rsa_oaep_encrypt_decrypt(keys_client):
    name = unique("crypt-oaep")
    key = keys_client.create_rsa_key(name, size=2048)
    crypto = _crypto(name, key.properties.version)

    encrypted = crypto.encrypt(EncryptionAlgorithm.rsa_oaep, b"hello-oaep")
    decrypted = crypto.decrypt(EncryptionAlgorithm.rsa_oaep, encrypted.ciphertext)
    assert decrypted.plaintext == b"hello-oaep"
    keys_client.begin_delete_key(name).result()


def test_rsa_oaep_256_wrap_unwrap(keys_client):
    name = unique("wrap")
    key = keys_client.create_rsa_key(name, size=2048)
    crypto = _crypto(name, key.properties.version)

    wrapped = crypto.wrap_key(KeyWrapAlgorithm.rsa_oaep_256, b"wrapped-material")
    unwrapped = crypto.unwrap_key(KeyWrapAlgorithm.rsa_oaep_256, wrapped.encrypted_key)
    assert unwrapped.key == b"wrapped-material"
    keys_client.begin_delete_key(name).result()


def test_rs256_sign_verify(keys_client):
    name = unique("sig-rs256")
    key = keys_client.create_rsa_key(name, size=2048)
    crypto = _crypto(name, key.properties.version)

    digest = hashlib.sha256(b"sign-me").digest()
    signature = crypto.sign(SignatureAlgorithm.rs256, digest).signature
    verified = crypto.verify(SignatureAlgorithm.rs256, digest, signature)
    assert verified.is_valid is True
    keys_client.begin_delete_key(name).result()


def test_es256_sign_verify(keys_client):
    name = unique("sig-es256")
    key = keys_client.create_ec_key(name, curve="P-256")
    crypto = _crypto(name, key.properties.version)

    digest = hashlib.sha256(b"sign-me").digest()
    signature = crypto.sign(SignatureAlgorithm.es256, digest).signature
    verified = crypto.verify(SignatureAlgorithm.es256, digest, signature)
    assert verified.is_valid is True
    keys_client.begin_delete_key(name).result()


def test_a256_gcm_encrypt_decrypt(keys_client):
    name = unique("crypt-gcm")
    key = keys_client.create_oct_key(name, size=256)
    crypto = _crypto(name, key.properties.version)

    result = crypto.encrypt(EncryptionAlgorithm.a256_gcm, b"gcm-plaintext")
    assert result.ciphertext is not None
    assert result.iv is not None
    assert result.tag is not None

    decrypted = crypto.decrypt(
        EncryptionAlgorithm.a256_gcm,
        result.ciphertext,
        iv=result.iv,
        authentication_tag=result.tag,
    )
    assert decrypted.plaintext == b"gcm-plaintext"
    keys_client.begin_delete_key(name).result()


# ---------------------------------------------------------------------------
# Managed HSM
# ---------------------------------------------------------------------------

def test_managed_hsm_basic_crud(hsm_keys_client):
    name = unique("hsm")
    created = hsm_keys_client.create_rsa_key(name, size=2048)
    assert created.name == name

    assert hsm_keys_client.get_key(name).name == name

    listed = [p.name for p in hsm_keys_client.list_properties_of_keys()]
    assert name in listed

    hsm_keys_client.begin_delete_key(name).result()
