# izgw-transform-sql

Optional SQL backend module for the IZ Gateway Transformation Service.

When included in the `izgw-transform` build (via the `sql-support` Maven profile),
this module adds support for querying ANSI SQL-compatible databases as FHIR
backends alongside the existing IZ Gateway path.

## Building

This module is not built standalone. It is included as a profile-activated
dependency in `izgw-transform`:

```
mvn package -P sql-support,sql-mssql
```

See `izgw-transform` for full build and deployment documentation.

## Documentation

Deployment documentation is in `docs/`. Content is delivered as GitHub Flavored
Markdown and can be converted to PDF or Word for formal delivery.
