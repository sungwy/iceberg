# Conformance fixtures (POC — submodule wired, test deferred)

This pins [`sungwy/iceberg-testing`](https://github.com/sungwy/iceberg-testing) as a
git submodule at `iceberg-testing/`: language-neutral conformance fixtures for
Apache Iceberg, modeled on `apache/parquet-testing`. Three surfaces:

- `table-spec/types/` — type-string parse + exact re-serialize (canonical)
- `table-spec/transforms/bucket/` — the Appendix B 32-bit bucket hash, as a
  Known-Answer-Test, including byte-boundary decimals and a non-BMP string
- `table-spec/delete-formats/` — positional and equality delete-file decode by
  field-id

A Java (JUnit) harness that walks these fixtures is **not included in this POC**. It
was prepared in an environment without a JVM build, so rather than commit an
unverified test, the wiring is left as a TODO. The PyIceberg fork has the worked
example, including how a consumer keeps a local staged-adoption list for cases it
does not yet satisfy: https://github.com/sungwy/iceberg-python/pull/1

To consume: `git submodule update --init`, then walk `iceberg-testing/table-spec/**`
and apply each surface README's assertion (parse + re-serialize; compute the bucket
hash and compare; decode delete-file columns by field-id).

The bucket Known-Answer-Test is the highest-signal surface to wire first: the
expected hashes are taken from the spec's Appendix B, so it is a direct check of
`BucketUtil` against the reference vectors plus byte-boundary decimal cases.
