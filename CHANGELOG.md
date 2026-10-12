# Changelog

All notable changes to this project will be documented in this file. 

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [6.6.0](https://github.com/NASA-AMMOS/tile_service/releases/tag/6.6.0)

### Security

* Server side request forgery (SSRF) filter for http[s] input URLs: hosts that resolve to loopback, private, link local, metadata, or other non-public addresses are rejected, including on redirects.
* http[s] input URLs (other than http[s] S3 URLs) now require a non-empty `rdr_url_whitelist_patterns`.
* Absolute filesystem input paths are now rejected; previously they were silently resolved relative to `fs_input_dir`.
* Connect and read timeouts for http[s] inputs.

## [6.5.8](https://github.com/NASA-AMMOS/tile_service/releases/tag/6.5.8)

* Initial open source release.
