# Devbox syncer

A one-way sync from laptop to an EC2 instance.

## Build

To prepare an assembly jar, ready to be tested and deployed in the universe/

```
$ ./mill launcher.assembly
```

The result can be found in `out/launcher/assembly/dest/out.jar`

## Tests

To run all tests (takes a long time):

```
$ ./mill devbox.test
```

## Interactive console (REPL)

```
$ ./mill -i devbox.repl
```

## Release

There is a [Github Action](https://github.com/databricks/devbox/actions?query=workflow%3ARelease) to release Devbox.

Just run the workflow on the target branch (usually master) with the new version number and check
the [releases](https://github.com/databricks/devbox/releases) page
