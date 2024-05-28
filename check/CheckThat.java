package check;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CheckThat {

    public static CheckThat it;

    // Needed for @DisabledIf(notApplicable) annotation;
    public static final String notApplicable = "check.CheckThat#theTypeDoesNotExist";

    public static boolean theTypeDoesNotExist() {
        return it == null;
    }

    private String[] packagePath;
    private static Set<String> imports = new HashSet<>();
    private Modifiers modifiers = new Modifiers();
    private String classType;  // required: "class", "enum", "interface"
    private String className;
    private String typeParameter;
    private String parentInfo;  // optional: "extends ...", "implements ..."

    private String[] enumElements;
    private ArrayList<Field> fields = new ArrayList<>();
    private ArrayList<Method> constructors = new ArrayList<>();
    private ArrayList<Method> fieldMethods = new ArrayList<>();
    private ArrayList<Method> classMethods = new ArrayList<>();

    private boolean hasEqualityCheck = false;
    private boolean hasOrdering = false;

    private Member inspectedMember;

    public CheckThat(String name, String type) {
        String[] nameParts = name.split("\\.");
        if (new File(String.join(File.separator, nameParts) + ".java").exists()) {
            throw new RuntimeException("File already exists!");
        }
        this.packagePath = Arrays.copyOf(nameParts, nameParts.length - 1);
        this.className = nameParts[nameParts.length - 1];
        this.classType = type;
    }

    public CheckThat(String name, String type, String parentInfo) {
        this(name, type);
        this.parentInfo = parentInfo;
    }

    /* INITIALIZATION */

    public static String withInterfaces(String... interfaces) {
        return "implements " + String.join(", ", map(interfaces, i -> new Variable(i).type));
    }

    public static String withInterface(String interfaceName) {
        return withInterfaces(interfaceName);
    }

    public static String withParent(String parent) {
        return "extends " + new Variable(parent).type;
    }

    public CheckThat withTypeParameter(String typeParameter) {
        return withTypeParameters(typeParameter);
    }

    public CheckThat withTypeParameters(String... params) {
        this.typeParameter = String.join(", ", params);
        return this;
    }

    public static CheckThat theClass(String name) {
        it = new CheckThat(name, "class");
        return it;
    }

    public static CheckThat theClass(String name, String parentinfo) {
        it = new CheckThat(name, "class", parentinfo);
        return it;
    }

    public static CheckThat theClassWithParent(String name, String parent) {
        return theClass(name, withParent(parent));
    }

    public static CheckThat theEnum(String name) {
        it = new CheckThat(name, "enum");
        return it;
    }

    public static CheckThat theInterface(String name) {
        it = new CheckThat(name, "interface");
        return it;
    }

    public static CheckThat theCheckedException(String name) {
        it = new CheckThat(name, "class", "extends Exception");
        return it;
    }

    public static CheckThat theUncheckedException(String name) {
        it = new CheckThat(name, "class", "extends RuntimeException");
        return it;
    }

    public static enum Condition {
        // thatIs() on a field or class
        USABLE_WITHOUT_INSTANCE(Type.STATICNESS, "static"),
        INSTANCE_LEVEL(Type.STATICNESS),
        MODIFIABLE(Type.MODIFIABILITY),
        NOT_MODIFIABLE(Type.MODIFIABILITY, "final"),
        FULLY_IMPLEMENTED(Type.ABSTRACTNESS),
        NOT_IMPLEMENTED(Type.ABSTRACTNESS, "abstract"),
        VISIBLE_TO_ALL(Type.VISIBILITY, "public"),
        VISIBLE_TO_PACKAGE(Type.VISIBILITY),
        VISIBLE_TO_SUBCLASSES(Type.VISIBILITY, "protected"),
        VISIBLE_TO_NONE(Type.VISIBILITY, "private"),

        // Not used in lab tests, but CheckThat technically contains it
        // INHERITED(Type.INHERITANCE),

        // thatHas() on a field
        GETTER(Type.REQUIRED_METHOD),
        SETTER(Type.REQUIRED_METHOD),

        // has() on a class
        TEXTUAL_REPRESENTATION(Type.REQUIRED_METHOD),
        DEFAULT_CONSTRUCTOR(Type.DEFAULT_CTOR),

        // thatHas() on a class (should be has() in my opinion)
        EQUALITY_CHECK(Type.REQUIRED_METHOD),
        NATURAL_ORDERING(Type.REQUIRED_METHOD);

        public final Type type;
        public final String modifier;

        private Condition(Type type, String modifier) {
            this.type = type;
            this.modifier = modifier;
        }

        private Condition(Type type) {
            this(type, null);
        }

        public static enum Type {
            STATICNESS,
            MODIFIABILITY,
            ABSTRACTNESS,
            VISIBILITY,
            REQUIRED_METHOD,
            INHERITANCE,
            DEFAULT_CTOR;
        }
    }

    /* ADDING MEMBERS */

    public static class Variable {

        public String name;
        public String type;

        private static int counter = 1;

        private static final Set<String> possibleImports = Set.of(
                "java.util.List",
                "java.util.ArrayList",
                "java.util.LinkedList",
                "java.util.Map",
                "java.util.HashMap",
                "java.util.Set",
                "java.util.HashSet",
                "java.util.Random"
        );

        public static void resetCounter() {
            counter = 1;
        }

        private boolean isPrimitive() {
            Set<String> primitives = Set.of("byte", "short", "int", "long", "float", "double", "char", "boolean");
            return primitives.contains(this.type);
        }

        public Variable(String typedName) {
            String[] parts = typedName.split(": ");
            switch (parts.length) {
                case 1:
                    name = "var" + counter++;
                    type = getTypeFromString(parts[0]);
                    break;
                case 2:
                    name = parts[0];
                    type = getTypeFromString(parts[1]);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid variable descriptor");
            }
        }

        private String getTypeFromString(String s) {
            String[] parts;

            if (!s.contains(" of ")) {
                return importAndSimplify(s);
            }

            parts = s.split(" of ", 2);
            parts[0] = importAndSimplify(parts[0]);
            switch (parts[0]) {
                case "array":
                    return getTypeFromString(parts[1]) + "[]";
                case "vararg":
                    return getTypeFromString(parts[1]) + "...";
                case "HashMap":
                    parts = parts[1].split(" to ", 2);
                    return "HashMap<" + getTypeFromString(parts[0]) + ", " + getTypeFromString(parts[1]) + ">";
                default:
                    return parts[0] + "<" + getTypeFromString(parts[1]) + ">";
            }
        }

        private static String importAndSimplify(String type) {
            if (type.contains(".")) {
                imports.add(type);
                String[] nameParts = type.split("\\.");
                return nameParts[nameParts.length - 1];
            }
            for (String className : possibleImports) {
                String[] importParts = className.split("\\.");
                if (type.equals(importParts[importParts.length - 1]) && !imports.contains(className)) {
                    imports.add(className);
                    break;
                }
            }
            return type;
        }

        @Override
        public String toString() {
            return type + " " + name;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, type);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || !(obj instanceof Variable)) {
                return false;
            }
            Variable v = (Variable) obj;
            return name.equals(v.name) && type.equals(v.type);
        }
    }

    public CheckThat hasField(String descriptor) {
        Field field = new Field(descriptor);
        fields.add(field);
        inspectedMember = field;
        return this;
    }

    public static String withParams(String... params) {
        Variable.resetCounter();
        return String.join(", ", map(params, param -> new Variable(param).toString()));
    }

    public static String withNoParams() {
        return "";
    }

    public static String withArgs(String... args) {
        return withParams(args);
    }

    public static String withArgsSimilarToFields() {
        return "/* args similar to fields */";
    }

    public static String withArgsSimilarToFields(String... fields) {
        return "/* " + String.join(", ", fields) + " */";
    }

    public static String withArgsAsInParent() {
        return "/* args as in parent */";
    }

    public static String withNoArgs() {
        return withNoParams();
    }

    public CheckThat hasMethod(String name, String parameters) {
        Method method = new Method(name, parameters);
        classMethods.add(method);
        inspectedMember = method;
        return this;
    }

    public CheckThat hasMethodWithNoParams(String name) {
        return hasMethod(name, "");
    }

    public CheckThat hasConstructor(String parameters) {
        Method constructor = new Method(className, parameters);
        constructors.add(constructor);
        inspectedMember = constructor;
        return this;
    }

    public CheckThat hasNoArgConstructor() {
        return hasConstructor(null);
    }

    public CheckThat hasEnumElements(String... elements) {
        enumElements = elements;
        writeToFile();
        return this;
    }

    public CheckThat thatReturns(String returnType) {
        ((Method) inspectedMember).returnType = new Variable(returnType).type;
        writeToFile();
        return this;
    }

    public CheckThat thatReturnsNothing() {
        return thatReturns("void");
    }

    public CheckThat thatCanRaise(String... exceptions) {
        ((Method) inspectedMember).exceptions = " throws " + String.join(", ", map(exceptions, e -> new Variable(e).type));
        return this;
    }

    public CheckThat implementsMethod(String method) {
        Method result = new Method(method, "", "// TODO: correct signature");
        result.annotations = "@Override";
        classMethods.add(result);
        return this;
    }

    public CheckThat has(Condition... conditions) {
        inspectedMember = null;
        for (Condition condition : conditions) {
            switch (condition) {
                case DEFAULT_CONSTRUCTOR:
                    break;
                case TEXTUAL_REPRESENTATION:
                    classMethods.add(toStringMethod());
                    break;
                case EQUALITY_CHECK:
                    hasEqualityCheck = true;
                    break;
                case NATURAL_ORDERING:
                    hasOrdering = true;
                    String comparableInterface = "Comparable<" + className + ">";
                    if (parentInfo == null) {
                        parentInfo = "implements" + comparableInterface;
                        break;
                    }
                    if (parentInfo.startsWith("implements")) {
                        parentInfo += ", " + comparableInterface;
                        break;
                    }
                    if (parentInfo.startsWith("extends")) {
                        parentInfo += " implements " + comparableInterface;
                        break;
                    }
                    throw new IllegalStateException();
                default:
                    throw new IllegalArgumentException();
            }
        }
        writeToFile();
        return this;
    }

    public CheckThat hasNo(Condition... conditions) {
        return this;
    }

    public CheckThat thatHas(Condition... conditions) {
        for (Condition condition : conditions) {
            switch (condition) {
                case GETTER:
                    fieldMethods.add(((Field) inspectedMember).getterMethod());
                    break;
                case SETTER:
                    fieldMethods.add(((Field) inspectedMember).setterMethod());
                    break;
                default:
                    has(condition);
            }
        }
        writeToFile();
        return this;
    }

    public CheckThat thatHasNo(Condition... conditions) {
        return this;
    }

    public CheckThat thatHasValue(int value) {
        ((Field) inspectedMember).initialValue = Integer.toString(value);
        writeToFile();
        return this;
    }

    public CheckThat thatHasValue(String value) {
        ((Field) inspectedMember).initialValue = "\"" + value + "\"";
        writeToFile();
        return this;
    }

    public CheckThat withInitialValue(int value) {
        return thatHasValue(value);
    }

    public CheckThat withInitialValue(String value) {
        return thatHasValue(value);
    }

    public CheckThat thatCalls(String... methods) {
        ((Method) inspectedMember).addToBody("// TODO: call " + String.join(", ", methods));
        return this;
    }

    public static String theParent(String... parameters) {
        return "the parent " + String.join(", ", parameters);
    }

    public static String theOtherConstructor(String... parameters) {
        return "the other constructor " + String.join(", ", parameters);
    }

    public static String with(String... parameters) {
        return "with " + String.join(", ", parameters);
    }

    public static String withAdditionalArgs(String... arguments) {
        return "with additional args: " + String.join(", ", arguments);
    }

    public CheckThat that(String... parameters) {
        ((Method) inspectedMember).addToBody("// TODO: " + String.join(", ", parameters));
        return this;
    }

    public static String createsEmpty(String... parameters) {
        return "create empty " + String.join(", ", parameters);
    }


    /* PROPERTIES OF CLASSES AND MEMBERS */

    class Member {

        Modifiers modifiers = new Modifiers();
    }

    class Modifiers {

        public String visibility;
        public String abstractness;
        public String staticness;
        public String modifiability;

        @Override
        public String toString() {
            return Stream.of(visibility, abstractness, staticness, modifiability)
                    .filter(s -> s != null)
                    .collect(Collectors.joining(" "));
        }
    }

    class Field extends Member {

        public Variable variable;
        public String initialValue;

        public Field(String typedName) {
            this.variable = new Variable(typedName);
            this.modifiers = new Modifiers();
        }

        public Method getterMethod() {
            String name = "get" + capitalize(variable.name);
            String body = "return " + variable.name + ";";
            Method getter = new Method(name, "", body);
            getter.modifiers.visibility = "public";
            getter.returnType = variable.type;
            return getter;
        }

        public Method setterMethod() {
            String name = "set" + capitalize(variable.name);
            String body = "this." + variable.name + " = " + variable.name + ";";
            Method setter = new Method(name, variable.toString(), body);
            setter.modifiers.visibility = "public";
            setter.returnType = "void";
            return setter;
        }

        private static String capitalize(String s) {
            return s.substring(0, 1).toUpperCase() + s.substring(1);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(modifiers.toString()).append(" ").append(variable.toString());
            if (initialValue != null) {
                sb.append(" = ").append(initialValue);
            }
            sb.append(";");
            return sb.toString();
        }
    }

    class Method extends Member {

        public String annotations;
        public String returnType;
        public String name;
        public String parameters;
        public String exceptions; // "throws ..."
        public String body;

        public Method(String name, String parameters) {
            this.name = name;
            this.parameters = parameters;
        }

        public Method(String name, String parameters, String body) {
            this(name, parameters);
            this.body = body;
        }

        public void addToBody(String s) {
            if (body == null) {
                body = "";
            }
            body += s;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();

            if (annotations != null) {
                sb.append(annotations).append("\n");
            }

            if (!classType.equals("interface") && !modifiers.toString().equals("")) {
                sb.append(modifiers.toString()).append(" ");
            }
            if (returnType != null) {
                sb.append(returnType).append(" ");
            }
            sb.append(name).append("(").append(parameters).append(")");

            if (exceptions != null) {
                sb.append(exceptions);
            }

            if (modifiers.abstractness != null) {
                return sb.append(";").toString();
            }

            sb.append(" {\n");
            if (body != null) {
                sb.append(indented(body)).append("\n");
            } else if (returnType != null && !returnType.equals("void")) {
                sb.append(indented("return " + getDefaultReturnValue(returnType) + ";")).append("\n");
            }
            sb.append("}");

            return sb.toString();
        }

        private String getDefaultReturnValue(String returnType) {
            if (returnType.equals("boolean")) {
                return "false";
            }
            return new Variable(returnType).isPrimitive() ? "0" : "null";
        }
    }

    private Method toStringMethod() {
        Method result = new Method("toString", "", "return super.toString();");
        result.annotations = "@Override";
        result.modifiers.visibility = "public";
        result.returnType = "String";
        return result;
    }

    private Method hashCodeMethod() {
        imports.add("java.util.Objects");
        ArrayList<String> fieldNames = new ArrayList<>();
        for (Field f : fields) {
            fieldNames.add(f.variable.name);
        }
        String body = "return Objects.hash(" + String.join(", ", fieldNames) + ");";
        Method result = new Method("hashCode", "", body);
        result.annotations = "@Override";
        result.modifiers.visibility = "public";
        result.returnType = "int";
        return result;
    }

    private Method equalsMethod() {
        ArrayList<String> comparisons = new ArrayList<>();
        for (Field f : fields) {
            String name = f.variable.name;
            if (f.variable.isPrimitive()) {
                comparisons.add(String.format("%s == t.%s", name, name));
            } else if (f.variable.type.endsWith("[]")) {
                imports.add("java.util.Arrays");
                comparisons.add(String.format("Arrays.equals(%s, t.%s)", name, name));
            } else {
                comparisons.add(String.format("%s.equals(%s)", name, name));
            }
        }

        String body = String.format(
                """
            if(that != null && getClass().equals(that.getClass())) {
            %1$s%2$s t = (%2$s)that;
            %1$sreturn %3$s;
            }
            return false;""",
                INDENTATION,
                className,
                String.join(" && ", comparisons)
        );

        Method result = new Method("equals", "Object that", body);
        result.annotations = "@Override";
        result.modifiers.visibility = "public";
        result.returnType = "boolean";
        return result;
    }

    private Method compareToMethod() {
        Method result = new Method("compareTo", className + " other");
        result.annotations = "@Override";
        result.modifiers.visibility = "public";
        result.returnType = "int";
        return result;
    }

    public CheckThat thatIs(Condition... conditions) {
        Modifiers modifiers = (inspectedMember != null) ? inspectedMember.modifiers : this.modifiers;

        for (Condition condition : conditions) {
            switch (condition.type) {
                case STATICNESS:
                    modifiers.staticness = condition.modifier;
                    break;
                case MODIFIABILITY:
                    modifiers.modifiability = condition.modifier;
                    break;
                case ABSTRACTNESS:
                    modifiers.abstractness = condition.modifier;
                    break;
                case VISIBILITY:
                    modifiers.visibility = condition.modifier;
                    break;
                default:
                    throw new IllegalArgumentException();
            }
        }

        writeToFile();
        return this;
    }

    public CheckThat thatIsInheritedFrom(String parent) {
        return this;
    }

    /* CODE GENERATION */
    
    @Override
    public String toString() {
        ArrayList<String> fileSections = new ArrayList<>();

        fileSections.add("package " + String.join(".", packagePath) + ";");

        if (!imports.isEmpty()) {
            ArrayList<String> importsList = new ArrayList<>(imports);
            Collections.sort(importsList);
            fileSections.add(String.join("\n", map(importsList, s -> "import " + s + ";")));
        }

        String classNameWithTypeParam = className + ((typeParameter != null) ? ("<" + typeParameter + ">") : "");

        String classHeader
                = Stream.of(modifiers.toString(), classType, classNameWithTypeParam, parentInfo, "{\n")
                        .filter(x -> x != null && !x.equals(""))
                        .collect(Collectors.joining(" "));

        ArrayList<String> bodySections = new ArrayList<>();

        if (enumElements != null) {
            bodySections.add(String.join(",\n", enumElements) + ";");
        }

        if (!fields.isEmpty()) {
            bodySections.add(String.join("\n", map(fields, f -> f.toString())));
        }

        ArrayList<Method> methods = new ArrayList<>();

        methods.addAll(constructors);
        methods.addAll(fieldMethods);
        methods.addAll(classMethods);

        if (hasEqualityCheck) {
            methods.add(hashCodeMethod());
            methods.add(equalsMethod());
        }

        if (hasOrdering) {
            methods.add(compareToMethod());
        }

        bodySections.add(String.join("\n\n", map(methods, m -> m.toString())));

        fileSections.add(classHeader + indented(String.join("\n\n", bodySections)) + "\n}");

        return String.join("\n\n", fileSections);
    }

    public static final String INDENTATION = "    ";

    private String indented(String text) {
        ArrayList<String> resultLines = new ArrayList<>();
        for (String line : text.split("\n")) {
            resultLines.add((line.equals("") ? "" : INDENTATION) + line);
        }
        return String.join("\n", resultLines);
    }

    public void writeToFile() {
        File targetFile = new File(String.join(File.separator, packagePath), className + ".java");
        File parent = targetFile.getParentFile();
        if (!parent.exists()) {
            parent.mkdirs();
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(targetFile))) {
            writer.write(this.toString());
        } catch (IOException e) {
            throw new RuntimeException(e.toString());
        }
    }

    public static <T> String toJoinedString(String separator, Iterable<T> elements) {
        if (elements == null) {
            return "";
        }
        ArrayList<String> stringElements = new ArrayList<>();
        for (T elem : elements) {
            stringElements.add(elem.toString());
        }
        return String.join(separator, stringElements);
    }

    public static <T, R> List<R> map(Iterable<T> elements, Function<T, R> function) {
        ArrayList<R> results = new ArrayList<>();
        if (elements == null) {
            return results;
        }
        for (T elem : elements) {
            results.add(function.apply(elem));
        }
        return results;
    }

    public static <T, R> List<R> map(T[] elements, Function<T, R> function) {
        return map(Arrays.asList(elements), function);
    }
}
