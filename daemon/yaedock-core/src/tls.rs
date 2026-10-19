//! The daemon's self-signed TLS identity, and certificate pinning for clients.
//!
//! The phone learns the certificate's SHA-256 fingerprint from the pairing QR
//! code and accepts only that certificate, so no certificate authority is needed.

use std::fs;
use std::io;
use std::path::Path;
use std::sync::Arc;

use anyhow::Context;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{CryptoProvider, ring};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::{
    CertificateError, ClientConfig, DigitallySignedStruct, ServerConfig, SignatureScheme,
};
use sha2::{Digest, Sha256};
use tracing::info;

use crate::paths;

const CERT_FILE: &str = "cert.der";
const KEY_FILE: &str = "key.der";

pub struct Identity {
    cert: CertificateDer<'static>,
    key: PrivatePkcs8KeyDer<'static>,
}

impl Identity {
    /// Loads the certificate from `dir`, creating one on first run.
    pub fn load_or_create(dir: &Path) -> anyhow::Result<Self> {
        match (fs::read(dir.join(CERT_FILE)), fs::read(dir.join(KEY_FILE))) {
            (Ok(cert), Ok(key)) => Ok(Self {
                cert: cert.into(),
                key: key.into(),
            }),
            (Err(err), _) | (_, Err(err)) if err.kind() == io::ErrorKind::NotFound => {
                Self::create(dir)
            }
            (Err(err), _) | (_, Err(err)) => {
                Err(err).with_context(|| format!("reading certificate from {}", dir.display()))
            }
        }
    }

    /// Loads an existing certificate without creating one.
    pub fn load(dir: &Path) -> anyhow::Result<Self> {
        let read = |file| {
            fs::read(dir.join(file)).with_context(|| {
                format!(
                    "reading {}; has the daemon run yet?",
                    dir.join(file).display()
                )
            })
        };
        Ok(Self {
            cert: read(CERT_FILE)?.into(),
            key: read(KEY_FILE)?.into(),
        })
    }

    fn create(dir: &Path) -> anyhow::Result<Self> {
        let hostname = yaedock_sys::metrics::SystemInfo::read().hostname;
        let names = vec!["yaedock".to_string(), hostname];
        let generated = rcgen::generate_simple_self_signed(names)?;
        let cert = generated.cert.der().clone();
        let key = PrivatePkcs8KeyDer::from(generated.signing_key.serialize_der());
        paths::write_private(&dir.join(KEY_FILE), key.secret_pkcs8_der())?;
        paths::write_private(&dir.join(CERT_FILE), &cert)?;
        let identity = Self { cert, key };
        info!(fingerprint = %identity.fingerprint(), "created a new TLS certificate");
        Ok(identity)
    }

    /// Lowercase hex SHA-256 of the certificate.
    pub fn fingerprint(&self) -> String {
        fingerprint(&self.cert)
    }

    pub fn server_config(&self) -> anyhow::Result<Arc<ServerConfig>> {
        let config = ServerConfig::builder_with_provider(provider())
            .with_safe_default_protocol_versions()?
            .with_no_client_auth()
            .with_single_cert(
                vec![self.cert.clone()],
                PrivateKeyDer::Pkcs8(self.key.clone_key()),
            )?;
        Ok(Arc::new(config))
    }
}

fn provider() -> Arc<CryptoProvider> {
    Arc::new(ring::default_provider())
}

fn fingerprint(cert: &CertificateDer<'_>) -> String {
    crate::devices::hex(&Sha256::digest(cert))
}

/// A client config that accepts only the certificate with this fingerprint.
pub fn pinned_client_config(fingerprint: &str) -> anyhow::Result<Arc<ClientConfig>> {
    let provider = provider();
    let verifier = PinnedCert {
        fingerprint: fingerprint.to_ascii_lowercase().replace(':', ""),
        provider: provider.clone(),
    };
    let config = ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(verifier))
        .with_no_client_auth();
    Ok(Arc::new(config))
}

#[derive(Debug)]
struct PinnedCert {
    fingerprint: String,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for PinnedCert {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if fingerprint(end_entity) == self.fingerprint {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::InvalidCertificate(
                CertificateError::ApplicationVerificationFailure,
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}
