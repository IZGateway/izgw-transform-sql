# izgw-transform-sql

Optional SQL backend module for the IZ Gateway Transformation Service.

When included in the `izgw-transform` build (via the `sql-support` Maven profile),
this module adds support for querying ANSI SQL-compatible databases as FHIR
backends alongside the existing IZ Gateway path.

## Building

```cmd
mvn clean package
mvn test
mvn dependency-check:check
```

This module builds as a library JAR and publishes to GitHub Packages. It does
not produce a runnable service image on its own.

To produce a SQL-enabled `izgw-transform` service image, activate the
`sql-support` profile and a driver profile in `izgw-transform`:

```
mvn package -P sql-support,sql-mssql
```

See `izgw-transform` for full service build and deployment documentation.

## Documentation

Deployment documentation is in `docs/`. Content is delivered as GitHub Flavored
Markdown and can be converted to PDF or Word for formal delivery.
