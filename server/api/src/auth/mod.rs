//! Passkey sign-in and bearer sessions (docs/SERVER.md §6).

use std::net::{IpAddr, SocketAddr};

use axum::{extract::ConnectInfo, http::Request};
use tower_governor::{GovernorError, key_extractor::KeyExtractor};

pub mod passkeys;
pub mod sessions;

/// Rate-limit key for `/v1/auth/*`: the client IP Cloudflare puts in
/// `CF-Connecting-IP`. Safe to trust because the host firewall admits 80/443
/// only from Cloudflare (§9). Without it (in-cluster), the TCP peer.
#[derive(Clone, Copy)]
pub struct ClientIp;

impl KeyExtractor for ClientIp {
    type Key = IpAddr;

    fn extract<T>(&self, req: &Request<T>) -> Result<IpAddr, GovernorError> {
        let header = req
            .headers()
            .get("cf-connecting-ip")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.trim().parse().ok());
        let peer = || {
            req.extensions()
                .get::<ConnectInfo<SocketAddr>>()
                .map(|c| c.0.ip())
        };
        // Only reachable without a peer address, i.e. in tests that call the router directly.
        Ok(header.or_else(peer).unwrap_or(IpAddr::from([0, 0, 0, 0])))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_key_is_cf_connecting_ip_else_the_peer() {
        let peer = ConnectInfo(SocketAddr::from(([10, 0, 0, 7], 4000)));
        let req = |cf: Option<&str>| {
            let mut b = Request::get("/");
            if let Some(ip) = cf {
                b = b.header("CF-Connecting-IP", ip);
            }
            let mut req = b.body(()).unwrap();
            req.extensions_mut().insert(peer);
            req
        };
        let key = |r| ClientIp.extract(&r).unwrap();
        assert_eq!(
            key(req(Some("203.0.113.9"))),
            IpAddr::from([203, 0, 113, 9])
        );
        assert_eq!(
            key(req(Some("2001:db8::1"))),
            "2001:db8::1".parse::<IpAddr>().unwrap()
        );
        assert_eq!(key(req(None)), IpAddr::from([10, 0, 0, 7]));
        assert_eq!(key(req(Some("not an ip"))), IpAddr::from([10, 0, 0, 7]));
    }
}
