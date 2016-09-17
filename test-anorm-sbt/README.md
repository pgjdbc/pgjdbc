# Test case for https://github.com/pgjdbc/pgjdbc/issues/639

## Dependencies

```
http://www.scala-sbt.org/download.html
```

## Database setup

```
createdb test
createuser -s test
```

## Running tests

```
sbt test
```
