Generates code from structural tests written for the proprietary
structural testing library CheckThat, built on top of JUnit5, which is
used in the introductory Java course of ELTE. It aims to generate code
that can compile and pass the structural tests with as little
modification as possible, and it manages to do pretty good at that (see
[Limitations](#Limitations) below).

# Usage

This library roughly implements the public API of CheckThat. That means
that valid structural tests written for CheckThat should compile fine
with this library linked instead of CheckThat. To do that

1. Make a copy of the original checkthat.jar file, so that you can
restore it later.
2. Grab the compiled jar file from the Releases page and overwrite
checkthat.jar with the file you downloaded.
3. Run the structural tests as usual, but it will generate code instead
of running structure tests.
4. Fix the generated code so that it compiles (see
[Limitations](#Limitations) below).
5. Restore the original checkthat.jar file and run the structural tests
to see if they pass.

# Limitations

- If a test imports classes that don't exist yet, those classes need to
be generated first before being able to run that test. As a consequence,
if two classes depend on each other (including their tests), then the
code can't be generated without temporarily modifying the test files. 
- You may need to refresh the file explorer panel of your editor/IDE
to detect the newly generated files (not really a limitation of this 
program, but good to know).
- Constructor arguments are sometimes missing, but this is always
indicated by a comment
- Some imports are not generated (notably your own packages)
- There are some functions in CheckThat that don't do anything and
therefore they are inconsistently used in the provided structure tests.
In those cases, this library will place a comment in the method body
describing what needs to be done.

Due to how CheckThat and JUnit5 operate these limitations are really
difficult or impossible to overcome.  If you find a way to do that, feel
free to contribute.

# Building from source

Run `./package.sh` or do what the commands tell you.
