# Scala Fiddle Core

Scala Fiddle provides an embeddable web component where the user can edit and run Scala code. The source code is
compiled to JavaScript on the server and then run in the browser.

To develop with scala-js version 1.x, run:

```
sbt "~; router/reStart; compilerServer1x/reStart"
```
or with scala-js 0.6.x:

```
sbt "~; router/reStart; compilerServer06x/reStart"
```



You can package as a Docker image with

```
sbt docker
```

Access the fiddle via browser at http://localhost:8080/embed
