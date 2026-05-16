# PayFlow Backend ![CI](https://github.com/Payflow-Organization/payflow-backend/actions/workflows/ci.yml/badge.svg) [![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=lakiidev_payflow&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=lakiidev_payflow) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=lakiidev_payflow&metric=coverage)](https://sonarcloud.io/summary/new_code?id=lakiidev_payflow) [![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=lakiidev_payflow&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=lakiidev_payflow) [![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=lakiidev_payflow&metric=sqale_index)](https://sonarcloud.io/summary/new_code?id=lakiidev_payflow) [![Docker](https://img.shields.io/badge/ghcr.io-latest-blue?logo=docker)](https://github.com/orgs/Payflow-Organization/packages/container/package/payflow-backend)

Production-grade payment processing backend. Designed with correctness
and reliability as first-class concerns, so every architectural decision
maps to a concrete failure mode that it does prevent.

## Architecture
Built using a hexagonal architecture with Domain-Driven Design. Aggregates own their invariants, 
the domain layer is free of infrastructure concerns, and every architectural decision maps to a concrete failure mode, which it prevents.

### Key design decisions

**Transactional Outbox** - the outbox mutation is written together with the wallet, or not at all.
Relay is responsible for independently picking unpublished events and pushing them to Kafka. Without this,
a crash between commit and publish causes a silent loss of a payment without any error.
