# Esper server image (docs/SERVER.md §1, §9). Build from the repo root:
#   docker buildx build -f server/Dockerfile --platform linux/amd64,linux/arm64 .
#
# The build stage runs once on the build host and cross-compiles both static
# musl binaries (linked by rust-lld, see .cargo/config.toml), so arm64 needs no
# QEMU. Each platform's final image then copies its own binary.
# shortcut: works while every crate is pure Rust (webauthn-rs 0.6 dropped
# OpenSSL); a crate that compiles C needs a musl C cross compiler added here.
FROM --platform=$BUILDPLATFORM rust:1.100.0-slim-trixie AS build
RUN rustup target add x86_64-unknown-linux-musl aarch64-unknown-linux-musl
WORKDIR /src
# rust-toolchain.toml is left out on purpose: the base tag pins the toolchain.
COPY server/Cargo.toml server/Cargo.lock ./
COPY server/.cargo .cargo
COPY server/.sqlx .sqlx
COPY server/migrations migrations
COPY server/rules rules
COPY server/api api
ENV SQLX_OFFLINE=true
RUN cargo build --release --locked \
      --target x86_64-unknown-linux-musl --target aarch64-unknown-linux-musl \
 && mkdir -p /out/amd64 /out/arm64 \
 && cp target/x86_64-unknown-linux-musl/release/esper-server /out/amd64/ \
 && cp target/aarch64-unknown-linux-musl/release/esper-server /out/arm64/

FROM gcr.io/distroless/static-debian13:nonroot
ARG TARGETARCH
COPY --from=build /out/${TARGETARCH}/esper-server /esper-server
# Content is baked in and validated at startup; invalid content stops the server.
COPY content/jobs /content/jobs
COPY content/monsters /content/monsters
ENV ESPER_CONTENT_DIR=/content
EXPOSE 8080
ENTRYPOINT ["/esper-server"]
