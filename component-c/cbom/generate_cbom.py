#!/usr/bin/env python3
"""Emits a CycloneDX 1.6 Cryptography Bill of Materials (CBOM) for the
Latticejack mTLS reference service, in either its "before" (classical) or
"after" (hybrid PQC) configuration.

This is NOT a general-purpose Java crypto static analyzer - deliberately
so. The plan's own scope for Component C draws a line between the baseline
CBOM ("emitted from the codebase before and after, so the migration is
auditable") and an *optional, explicitly out-of-scope* AI hook that would
triage arbitrary scanner findings ("is this RSA in a live handshake or a
test fixture?"). Building a real static analyzer capable of that
distinction reliably is a much larger, riskier undertaking than this
component needs to take on.

Instead, this generates an accurate CBOM from the project's own known,
verified configuration - the same crypto assets this project has spent
this entire session verifying are real (not assumed) via actual
handshakes: see MIGRATION.md, ProviderBootstrap.java's NAMED_GROUPS, and
scripts/gen-classical-keys.sh's -groupname secp256r1. Each asset below
cites exactly where that fact comes from, so this stays auditable itself,
not just an auditing tool.

Honest by construction, not by discipline: the "after" CBOM still lists
ECDSA P-256 as a signature asset, because ML-DSA certificate authentication
is explicitly NOT implemented in this project (deferred - see MIGRATION.md
"Scope" and README.md's "ML-DSA certificate auth" row). A CBOM that quietly
dropped the still-classical asset just because the KEX is now PQC would be
misleading precisely where an auditable migration record needs to not be.
"""
import argparse
import json
import sys
import uuid

CDX_VERSION = "1.6"

X25519 = {
    "type": "cryptographic-asset",
    "name": "X25519",
    "description": "Elliptic-curve Diffie-Hellman key agreement (Curve25519), TLS 1.3 key exchange group.",
    "cryptoProperties": {
        "assetType": "algorithm",
        "algorithmProperties": {
            "primitive": "key-agree",
            "curve": "x25519",
            "executionEnvironment": "software-plain-ram",
            "implementationPlatform": "generic",
            "cryptoFunctions": ["keygen", "keyderive"],
            "classicalSecurityLevel": 128,
            "nistQuantumSecurityLevel": 0,
        },
    },
    "evidence": {
        "occurrences": [{
            "location": "ProviderBootstrap.java NAMED_GROUPS; scripts/require-jdk21.sh-pinned JSSE default groups"
        }]
    },
}

ECDSA_P256 = {
    "type": "cryptographic-asset",
    "name": "ECDSA-P256",
    "description": "Elliptic Curve Digital Signature Algorithm over secp256r1 (NIST P-256), used for both the leaf and CA certificate signatures in this project's test PKI.",
    "cryptoProperties": {
        "assetType": "algorithm",
        "algorithmProperties": {
            "primitive": "signature",
            "curve": "secp256r1",
            "executionEnvironment": "software-plain-ram",
            "implementationPlatform": "generic",
            "cryptoFunctions": ["keygen", "sign", "verify"],
            "classicalSecurityLevel": 128,
            "nistQuantumSecurityLevel": 0,
        },
    },
    "evidence": {
        "occurrences": [{
            "location": "scripts/gen-classical-keys.sh: keytool -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA"
        }]
    },
}

MLKEM768 = {
    "type": "cryptographic-asset",
    "name": "ML-KEM-768",
    "description": "Module-Lattice-Based Key-Encapsulation Mechanism (FIPS 203), NIST security category 3. Half of the hybrid X25519MLKEM768 TLS 1.3 key exchange group in this project's \"after\" configuration.",
    "cryptoProperties": {
        "assetType": "algorithm",
        "algorithmProperties": {
            "primitive": "kem",
            "parameterSetIdentifier": "768",
            "executionEnvironment": "software-plain-ram",
            "implementationPlatform": "generic",
            "cryptoFunctions": ["keygen", "encapsulate", "decapsulate"],
            "classicalSecurityLevel": 192,
            "nistQuantumSecurityLevel": 3,
        },
    },
    "evidence": {
        "occurrences": [{
            "location": "ProviderBootstrap.java NAMED_GROUPS[0]=\"X25519MLKEM768\"; negotiation verified via HelloRetryRequest detection in run-after.sh (see docs/bouncycastle-pqc-notes.md §3a)"
        }]
    },
}

X25519MLKEM768_COMBINER = {
    "type": "cryptographic-asset",
    "name": "X25519MLKEM768",
    "description": "Hybrid TLS 1.3 key exchange combiner: concatenates the X25519 ECDH shared secret and the ML-KEM-768 shared secret per draft-ietf-tls-hybrid-design, so the connection stays classically secure even if ML-KEM's novel security assumption is later broken, and post-quantum secure even if ECDH is broken by a future quantum computer.",
    "cryptoProperties": {
        "assetType": "algorithm",
        "algorithmProperties": {
            "primitive": "combiner",
            "executionEnvironment": "software-plain-ram",
            "implementationPlatform": "generic",
            "cryptoFunctions": ["keyderive"],
            "classicalSecurityLevel": 128,
            "nistQuantumSecurityLevel": 3,
        },
    },
    "evidence": {
        "occurrences": [{
            "location": "ProviderBootstrap.java NAMED_GROUPS[0]; BouncyCastle BCJSSE 1.85 hybrid group implementation"
        }]
    },
}


def make_app_component(config):
    label = "before (classical)" if config == "before" else "after (hybrid PQC)"
    return {
        "type": "application",
        "bom-ref": f"latticejack-echo-tls-{config}",
        "name": "latticejack-echo-tls",
        "version": config,
        "description": f"Latticejack mTLS reference service, {label} configuration.",
    }


def build_bom(config):
    app = make_app_component(config)
    if config == "before":
        crypto_assets = [X25519, ECDSA_P256]
    elif config == "after":
        crypto_assets = [X25519MLKEM768_COMBINER, MLKEM768, X25519, ECDSA_P256]
    else:
        raise ValueError(f"unknown config: {config}")

    components = [app] + crypto_assets
    for c in crypto_assets:
        c.setdefault("bom-ref", f"{config}-{c['name']}")

    dependencies = [{
        "ref": app["bom-ref"],
        "dependsOn": [c["bom-ref"] for c in crypto_assets],
    }]

    return {
        "bomFormat": "CycloneDX",
        "specVersion": CDX_VERSION,
        "serialNumber": f"urn:uuid:{uuid.uuid4()}",
        "version": 1,
        "metadata": {
            "component": app,
        },
        "components": components,
        "dependencies": dependencies,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", choices=["before", "after"])
    parser.add_argument("-o", "--output", help="output file (default: stdout)")
    args = parser.parse_args()

    bom = build_bom(args.config)
    out = json.dumps(bom, indent=2)
    if args.output:
        with open(args.output, "w") as f:
            f.write(out + "\n")
    else:
        print(out)


if __name__ == "__main__":
    main()
