# AWS Deployment Guide — SQL-Enabled Transformation Service

This guide covers the AWS infrastructure required to run the SQL-enabled
`izgw-transform-sql` module alongside the standard IZ Gateway Transformation
Service. It targets AWS deployments. Azure SQL Server deployment details will
be covered in a future revision.

---

## AWS Resources Provisioned

All resources below were created in account `357442695278`, region `us-east-1`,
as part of the [IGDD-3013](https://izgateway.atlassian.net/browse/IGDD-3013)
WA DOH SQL backend pilot.

### EFS Access Point

The SQL service configuration files (mapping YAML, endpoint config) are stored
on the shared IZ Gateway EFS filesystem alongside the existing transform service.

| Property | Value |
|---|---|
| Filesystem | `fs-0c76fe796cfc1d1e8` (izgateway-DevandTest-efs) |
| Access Point | `fsap-09a405984ca47c9d7` (xform-service-sql) |
| EFS Path | `/dev/xform-service-sql` |
| Container Mount | `/configuration` |

Place the following files on EFS at the access point root before starting the service:
- `<backend-name>.yml` — SQL endpoint configuration (datasource URL, table names, mapping path, credentials secret name)
- `sql-mapping.yml` — column-to-FHIR mapping configuration (or override via `sql.mapping.config-path`)

### ECS Task Definition

A dedicated task definition is registered for the SQL-enabled service:

| Property | Value |
|---|---|
| Family | `xform-service-sql-dev` |
| Current Revision | `3` |
| Base | Derived from `xform-service-alb-dev`; same task/execution role |
| Image | `357442695278.dkr.ecr.us-east-1.amazonaws.com/transformation-service-sql:latest` |
| EFS Mount | Access point `fsap-00296663339c03fd0` (xform-service-alb-dev — shares keystores with standard service) → `/configuration` |
| Log Group | `/ecs/xform-service-sql-dev` |

**SQL-specific environment variables to add when deploying:**

```
SQL_BACKEND_<NAME>=/configuration/<name>.yml
```

Where `<NAME>` is the backend name (e.g., `WAIIS`) and the value is the path to
the backend config file on EFS. Spring's relaxed binding makes `SQL_BACKEND_WAIIS`
equivalent to `sql.backend.waiis` in `application.yml`.

### ALB — Host-Header Listener Rule

The SQL-enabled service shares the existing `xform-service-alb-dev` ALB.
Traffic is routed by hostname.

| Property | Value |
|---|---|
| ALB | `xform-service-alb-dev` |
| DNS Hostname | `dev.sql-xform.izgateway.org` |
| Listener Rule | Priority 10; `host-header = dev.sql-xform.izgateway.org` |
| Target Group | `xform-service-sql-dev-tg` (`e322437bd01ba0bb`) |
| Certificate | ACM `2fe89e9d-c583-4513-ac48-daf6f12bda8e` (**issued** — added to ALB listener) |
| WAF | `xform-service-alb-dev-waf` (inherited from ALB — no change) |
| mTLS | `verify` mode, trust store `izgateway-dev-truststore` (inherited — no change) |
| SSL Policy | `ELBSecurityPolicy-TLS13-1-2-Res-FIPS-2023-04` (inherited — no change) |

**Route 53 records created:**
- `_3c4abefde7201689b91313819934b96e.dev.sql-xform.izgateway.org` CNAME → ACM validation target ✅
- `dev.sql-xform.izgateway.org` A alias → ALB ✅

ACM certificate issued and added to ALB listener. ✅

### IAM — Task Role Addition

The existing shared task role `izgateway-dev-izgateway-service` has an inline
policy added:

| Policy | Type | Purpose |
|---|---|---|
| `izgateway-dev-xform-sql-rds-connect` | Inline | Grants `rds-db:connect` on all RDS instances (future-proofing for IAM-auth backends) |

**Note:** IAM database authentication is not supported for SQL Server on AWS RDS.
SQL Server credentials are retrieved from Secrets Manager at startup.
`SecretsManagerReadWrite` (already on the role) covers credential retrieval.

**Note:** The role is at the 10-policy managed policy limit. The RDS policy was
added as an inline policy to avoid the quota.

### RDS Security Group

A dedicated security group isolates the SQL Server RDS instance:

| Property | Value |
|---|---|
| Security Group | `sg-0bd744034a29d160f` (xform-service-sql-dev-rds-sg) |
| Inbound Rule | TCP 1433 from `sg-0673ad74bd304fe8f` (xform ECS task SG) only |
| VPC | `vpc-03bd3ca016c59ca45` |

**Customer note:** ECS Fargate tasks use the default allow-all egress policy.
If your deployment restricts outbound traffic, ensure port 1433 outbound is
permitted from the ECS task security group to your database security group.

### Secrets Manager — Database Credentials

| Property | Value |
|---|---|
| Secret Name | `xform-dev-sql-credentials` |
| ARN | `arn:aws:secretsmanager:us-east-1:357442695278:secret:xform-dev-sql-credentials-GWl9n2` |
| Keys | `username`, `password` |

Update the secret with real credentials before deploying against a live database:

```
aws secretsmanager put-secret-value \
  --secret-id xform-dev-sql-credentials \
  --secret-string '{"username":"<db-user>","password":"<db-password>"}'
```

The secret name is referenced in the SQL endpoint configuration file on EFS
(not in any environment variable or `application.yml`). `SqlBackendAutoConfiguration`
retrieves and injects credentials programmatically at startup.

---

## ECS Service

The `xform-service-sql-dev` ECS service is deployed and running. ✅

| Property | Value |
|---|---|
| Cluster | `xform-service-alb-dev` |
| Service | `xform-service-sql-dev` |
| Task definition | `xform-service-sql-dev:3` |
| Launch type | FARGATE |
| Subnets | `subnet-0dc96d78ad16dc302`, `subnet-013094d0d72b913c4` |
| Security group | `sg-0673ad74bd304fe8f` (shared with standard xform service) |
| Target group | `xform-service-sql-dev-tg` |
| Desired count | 1 |

## ECR Repository

A dedicated ECR repository holds the SQL-enabled image, separate from the standard
`transformation-service` repository for CVE surface area isolation.

| Property | Value |
|---|---|
| Repository | `transformation-service-sql` |
| URI | `357442695278.dkr.ecr.us-east-1.amazonaws.com/transformation-service-sql` |
| Tag convention | `latest` (plus `IMAGE_TAG` and `IMAGE_BRANCH_TAG` per build) |
| Inspector scanning | **Enhanced (continuous)** via AWS Inspector |

The SQL-enabled image is built by the `izgw-transform` CI/CD pipeline when
`is_sql_run=true` (manual `workflow_dispatch` or triggered by `izgw-transform-sql`
develop merge). See `.github/workflows/maven.yml` in `izgw-transform`.

---

## Conventions for This Repository

Documentation in `izgw-transform-sql` is delivered as GitHub Flavored Markdown
and can be converted to PDF or Word for formal delivery using pandoc:

```
pandoc docs/aws-sql-deployment.md -f gfm -t docx -o aws-sql-deployment.docx
```
