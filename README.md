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
