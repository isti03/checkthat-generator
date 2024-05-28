#!/bin/bash

set -e
shopt -s globstar

javac check/*.java

zip checkthat.jar check/*.class META-INF/MANIFEST.MF