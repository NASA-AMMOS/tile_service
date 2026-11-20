# tile_service testing container

A manual testing environment: Tomcat 9 on a Java 21 JRE serving the locally built
`image_tiler.war` (localhost-fscache configuration), with the TileCLI jar at
`/opt/tile_service/image_tiler.jar`. It is not used by CI; the feature tests
start their own Tomcat (see [docs/TESTING.md](../docs/TESTING.md)).

No internal repository access is needed: the artifacts come from
`tests/e2e/scripts/build-product.sh`, which builds with public dependencies only.

```
bash tests/e2e/scripts/build-product.sh     # tests/e2e/.cache/build/image_tiler.{war,jar}
npm --prefix tests/e2e run data:fast         # public Mars 2020 products in tests/e2e/.cache/data
./testing-container/run.sh                   # http://localhost:8080/image_tiler/version
```

The data directory is mounted read-only at `test/data` under Tomcat's working
directory, so images are requested as `data/<file>`:

```
http://localhost:8080/image_tiler?image=data/NLG_1618_0810580175_755FDR_N0790198NCAM00518_0A02I3J01.IMG&viewer=true&proxy=http
```

Options: `--data DIR` serves another directory, `--port N` maps another host
port, and arguments after `--` go to `docker run`. Tiles are cached inside the
container and discarded when it stops.
