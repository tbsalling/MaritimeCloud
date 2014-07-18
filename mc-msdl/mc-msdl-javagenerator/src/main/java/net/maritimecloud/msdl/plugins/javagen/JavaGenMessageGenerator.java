/* Copyright (c) 2011 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.maritimecloud.msdl.plugins.javagen;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import net.maritimecloud.core.serialization.Message;
import net.maritimecloud.core.serialization.MessageReader;
import net.maritimecloud.core.serialization.MessageSerializer;
import net.maritimecloud.core.serialization.MessageWriter;
import net.maritimecloud.core.serialization.ValueSerializer;
import net.maritimecloud.internal.msdl.parser.antlr.StringUtil;
import net.maritimecloud.internal.serialization.Hashing;
import net.maritimecloud.internal.serialization.MessageHelper;
import net.maritimecloud.msdl.model.Annotatable;
import net.maritimecloud.msdl.model.BaseMessage;
import net.maritimecloud.msdl.model.BaseType;
import net.maritimecloud.msdl.model.EndpointDefinition;
import net.maritimecloud.msdl.model.EndpointMethod;
import net.maritimecloud.msdl.model.FieldOrParameter;
import net.maritimecloud.msdl.model.ListOrSetType;
import net.maritimecloud.msdl.model.MapType;
import net.maritimecloud.msdl.model.MsdlFile;
import net.maritimecloud.msdl.model.Type;
import net.maritimecloud.msdl.plugins.javagen.annotation.JavaImplements;
import net.maritimecloud.net.BroadcastMessage;
import net.maritimecloud.util.Binary;

import org.cakeframework.internal.codegen.CodegenClass;
import org.cakeframework.internal.codegen.CodegenMethod;

/**
 *
 * @author Kasper Nielsen
 */
public class JavaGenMessageGenerator {
    final CodegenClass c;

    final CodegenClass parent;

    final Annotatable anno;

    final List<FieldOrParameter> fields;

    final String name;

    final MsdlFile file;

    final boolean isMessage;

    final String serializerName;

    JavaGenMessageGenerator(CodegenClass parent, String name, EndpointDefinition def, EndpointMethod met) {
        this.parent = parent;
        this.anno = def;
        this.name = name;
        this.file = def.getFile();
        this.fields = met.getParameters();
        this.c = parent == null ? new CodegenClass() : parent.addInnerClass();
        this.isMessage = false;
        this.serializerName = name + "Serializer";
    }

    JavaGenMessageGenerator(CodegenClass parent, BaseMessage msg) {
        this.parent = parent;
        this.anno = msg;
        this.name = msg.getName();
        this.file = msg.getFile();
        this.fields = msg.getFields();
        this.c = parent == null ? new CodegenClass() : parent.addInnerClass();
        this.isMessage = true;
        this.serializerName = "Serializer";
    }

    void generateClass() {
        String mType;
        if (this instanceof JavaGenBroadcastMessageGenerator) {
            // c.imports().addExplicitImport(ClassDefinitions.BROADCAST_MESSAGE_CLASS);
            c.addImport(BroadcastMessage.class);
            mType = BroadcastMessage.class.getSimpleName();
        } else {
            c.addImport(Message.class);
            mType = Message.class.getSimpleName();
        }

        String imple = "";
        if (anno.isAnnotationPresent(JavaImplements.class)) {
            for (String s : anno.getAnnotation(JavaImplements.class).value()) {
                imple += ", " + s;
            }
        }

        if (parent == null) {
            c.setDefinition("public class ", name, " implements ", mType, imple);
        } else {
            c.setDefinition("public static class ", name, " implements ", mType, imple);
        }

        String fullName = file.getNamespace() + "." + name;
        c.addFieldWithJavadoc("The full name of this message.", "public static final String NAME = \"", fullName, "\";");

        c.addImport(MessageSerializer.class);
        c.addFieldWithJavadoc("A message serializer that can read and write instances of this class.",
                "public static final ", MessageSerializer.class, "<", name, "> SERIALIZER = new ", serializerName,
                "();");
    }

    JavaGenMessageGenerator generate() {
        generateClass();
        generateFields();
        // We only generate constructors if we have at least one field, otherwise we rely on default constructors
        // generated by javac
        if (fields.size() > 0) {
            generateConstructorEmpty();
            generateConstructorParser();
            if (isMessage) {
                generateConstructorImmutable();
            }
        }
        generateWriteTo(c, fields);
        generateAccessors();
        generateParser();
        JavaGenMessageImmutableGenerator.generate(this);
        generateToFrom();
        if (isMessage) {
            generateHashCode();
            generateEquals();
        }

        return this;
    }

    void generateConstructorEmpty() {
        CodegenMethod m = c.addMethod("public ", c.getSimpleName(), "()");
        m.addJavadoc("Creates a new ", c.getSimpleName(), ".");
        for (FieldOrParameter f : fields) {
            BaseType t = f.getType().getBaseType();
            if (t == BaseType.LIST) {
                m.add(f.getName(), " = new java.util.ArrayList<>();");
            } else if (t == BaseType.SET) {
                m.add(f.getName(), " = new java.util.LinkedHashSet<>();");
            } else if (t == BaseType.MAP) {
                m.add(f.getName(), " = new java.util.LinkedHashMap<>();");
            }
        }
    }

    void generateConstructorParser() {
        CodegenMethod m = c.addMethod(c.getSimpleName(), "(", MessageReader.class, " reader) throws IOException");
        m.addJavadoc("Creates a new ", c.getSimpleName(), " by reading from a message reader.");
        m.addJavadocParameter("reader", "the message reader");
        // TODO replace with generateParseMethod
        for (FieldOrParameter f : fields) {
            JavaGenType ty = new JavaGenType(f.getType());
            BaseType type = f.getType().getBaseType();
            String tagName = "(" + f.getTag() + ", \"" + f.getName() + "\"";
            if (type.isPrimitive()) {
                m.add("this.", f.getName(), " = reader.read", ty.writeReadName(), tagName, ", null);");
            } else if (type == BaseType.ENUM) {
                m.add("this.", f.getName(), " = reader.readEnum", tagName, ", ", ty.render(c), ".SERIALIZER);");
            } else if (type == BaseType.MESSAGE) {
                m.add("this.", f.getName(), " = reader.readMessage", tagName, ", ", ty.render(c), ".SERIALIZER);");
            } else if (type == BaseType.LIST) { // Complex type
                ListOrSetType los = (ListOrSetType) f.getType();
                c.addImport(MessageHelper.class);
                m.add("this.", f.getName(), " = ", MessageHelper.class, ".readList", tagName, ", reader, ",
                        complexParser(c, los.getElementType()), ");");
            } else if (type == BaseType.SET) { // Complex type
                c.addImport(MessageHelper.class);
                ListOrSetType los = (ListOrSetType) f.getType();
                m.add("this.", f.getName(), " = ", MessageHelper.class, ".readSet", tagName, ", reader, ",
                        complexParser(c, los.getElementType()), ");");
            } else { // Complex type
                c.addImport(MessageHelper.class);
                MapType los = (MapType) f.getType();
                m.add("this.", f.getName(), " = ", MessageHelper.class, ".readMap", tagName, ", reader, ",
                        complexParser(c, los.getKeyType()), ", ", complexParser(c, los.getValueType()), ");");
            }
        }
    }

    static String generateParseMethod(String readerName, CodegenClass c, FieldOrParameter f) {
        BaseType type = f.getType().getBaseType();
        JavaGenType ty = new JavaGenType(f.getType());
        ty.addImports(c);
        if (type.isPrimitive()) {
            return readerName + ".read" + ty.writeReadName() + "(" + f.getTag() + ", \"" + f.getName() + "\", null);";
        } else if (type == BaseType.ENUM) {
            return readerName + ".readEnum(" + f.getTag() + ", \"" + f.getName() + "\", " + ty.render(c)
                    + ".SERIALIZER);";
        } else if (type == BaseType.MESSAGE) {
            return readerName + ".readMessage(" + f.getTag() + ", \"" + f.getName() + "\", " + ty.render(c)
                    + ".SERIALIZER);";
        } else if (type == BaseType.LIST) { // Complex type
            ListOrSetType los = (ListOrSetType) f.getType();
            c.addImport(MessageHelper.class);
            return MessageHelper.class.getSimpleName() + ".readList(" + f.getTag() + ", \"" + f.getName() + "\", "
            + readerName + ", " + complexParser(c, los.getElementType()) + ");";
        } else if (type == BaseType.SET) { // Complex type
            ListOrSetType los = (ListOrSetType) f.getType();
            c.addImport(MessageHelper.class);
            return MessageHelper.class.getSimpleName() + ".readSet(" + f.getTag() + ", \"" + f.getName() + "\", "
            + readerName + ", " + complexParser(c, los.getElementType()) + ");";
        } else { // Complex type
            MapType los = (MapType) f.getType();
            c.addImport(MessageHelper.class);
            return MessageHelper.class.getSimpleName() + ".readMap(" + f.getTag() + ", \"" + f.getName() + "\", "
            + readerName + ", " + complexParser(c, los.getKeyType()) + ", "
            + complexParser(c, los.getValueType()) + ");";
        }
    }

    void generateConstructorImmutable() {
        CodegenMethod m = c.addMethod(c.getSimpleName(), "(", c.getSimpleName(), " instance)");
        m.addJavadoc("Creates a new ", c.getSimpleName(), " by copying an existing.");
        m.addJavadocParameter("instance", "the instance to copy all fields from");
        for (FieldOrParameter f : fields) {
            BaseType t = f.getType().getBaseType();
            if (t == BaseType.LIST || t == BaseType.SET || t == BaseType.MAP) {
                c.addImport(MessageHelper.class);
                m.add("this.", f.getName(), " = ", MessageHelper.class, ".immutableCopy(instance." + f.getName(), ");");
            } else if (t == BaseType.MESSAGE) {
                c.addImport(MessageHelper.class);
                m.add("this.", f.getName(), " = ", MessageHelper.class, ".immutable(instance." + f.getName(), ");");
            } else {
                m.add("this.", f.getName(), " = instance." + f.getName(), ";");
            }
        }
    }


    static String complexParser(CodegenClass c, Type type) {
        if (type == null) {
            return "null";
        }
        BaseType b = type.getBaseType();
        if (b.isPrimitive()) {
            c.addImport(ValueSerializer.class);
            if (b == BaseType.BINARY) {
                c.addImport(Binary.class);
            }
            return ValueSerializer.class.getSimpleName() + "." + b.name().toUpperCase();
        } else if (b.isReferenceType()) {
            JavaGenType ty = new JavaGenType(type);
            return ty.render(c) + ".SERIALIZER";
        } else if (b == BaseType.LIST) {
            ListOrSetType los = (ListOrSetType) type;
            return complexParser(c, los.getElementType()) + ".listOf()";
        } else if (b == BaseType.SET) {
            ListOrSetType los = (ListOrSetType) type;
            return complexParser(c, los.getElementType()) + ".setOf()";
        } else {
            MapType los = (MapType) type;
            return "MessageParser.ofMap(" + complexParser(c, los.getKeyType()) + ", "
            + complexParser(c, los.getValueType()) + ")";
        }
    }

    void generateFields() {
        for (FieldOrParameter f : fields) {
            JavaGenType ty = new JavaGenType(f.getType());
            ty.addImports(c);
            String init = "private " + (f.getType().getBaseType().isComplexType() ? "final " : "");
            // MSDLBaseType t = f.getType().getBaseType();
            // if (t == MSDLBaseType.LIST) {
            // init = " = new java.util.ArrayList<>()";
            // } else if (t == MSDLBaseType.SET) {
            // init = " = new java.util.LinkedHashSet<>()";
            // } else if (t == MSDLBaseType.MAP) {
            // init = " = new java.util.LinkedHashMap<>()";
            // }
            c.addFieldWithJavadoc("Field", init, ty.render(c), " ", f.getName(), ";");
        }
    }

    void generateHashCode() {
        CodegenMethod m = c.addMethod("public int hashCode()");
        m.addAnnotation(Override.class).addJavadoc("{@inheritDoc}");
        if (fields.size() == 0) {
            m.add("return ", c.getSimpleName().hashCode(), ";");
        } else if (fields.size() == 1) {
            m.add("return ", generateHashCode(fields.get(0)), ";");
        } else {
            m.add("int result = 31 + ", generateHashCode(fields.get(0)), ";");
            for (int i = 1; i < fields.size() - 1; i++) {
                m.add("result = 31 * result + ", generateHashCode(fields.get(i)), ";");
            }
            m.add("return 31 * result + ", generateHashCode(fields.get(fields.size() - 1)), ";");
        }
    }

    String generateHashCode(FieldOrParameter f) {
        // Comment in again if we get primitive datatypes
        // if (f.getType().getBaseType() == MSDLBaseType.INT32) {
        // return f.getName();
        // } else {
        c.addImport(Hashing.class);
        return "Hashing.hashcode(this." + f.getName() + ")";
        // }
    }

    void generateToFrom() {
        CodegenMethod m = c.addMethod("public String toJSON()");
        m.addJavadoc("Returns a JSON representation of this message");
        if (isMessage) {
            c.addImport(MessageSerializer.class);
            m.add("return ", MessageSerializer.class, ".writeToJSON(this, SERIALIZER);");

            CodegenMethod from = c.addMethod("public static ", c.getSimpleName(), " fromJSON(", CharSequence.class,
                    " c)");
            from.addJavadoc("Creates a message of this type from a JSON throwing a runtime exception if the format of the message does not match");
            from.add("return ", MessageSerializer.class, ".readFromJSON(SERIALIZER, c);");
        } else {
            m.throwNewUnsupportedOperationException("method not supported");
        }
    }

    //
    // public static HelloWorld fromJSON(CharSequence c) {
    // return MessageSerializer.readFromJSON(PARSER, c);
    // }

    void generateEquals() {
        CodegenMethod m = c.addMethod("public boolean equals(Object other)");
        m.addJavadoc("{@inheritDoc}").addAnnotation(Override.class);
        if (fields.isEmpty()) {
            m.add("return other == this || other instanceof ", c.getSimpleName(), ";");
            return;
        }

        m.add("if (other == this) {");
        m.add("return true;");
        m.add("} else if (other instanceof ", c.getSimpleName(), ") {");
        m.add(c.getSimpleName(), " o = (", c.getSimpleName(), ") other;");
        for (int i = 0; i < fields.size(); i++) {
            StringBuilder b = i == 0 ? new StringBuilder("return ") : new StringBuilder("       ");
            FieldOrParameter f = fields.get(i);
            // JavaGenType t = new JavaGenType(f.getType());
            // Check data typen
            // if (false /* fields.get(i).getType().getBaseType().isPrimitive() */) {
            // b.append("this." + f.getName() + " == o." + f.getName());
            // } else {
            c.addImport(Objects.class);
            b.append("Objects.equals(" + f.getName() + ", o." + f.getName() + ")");
            // }
            b.append(i == fields.size() - 1 ? ";" : " &&");
            m.add(b);
        }

        m.add("}");
        m.add("return false;");
    }

    static void generateWriteTo(CodegenClass c, Collection<FieldOrParameter> fields) {
        if (fields.size() > 0) {
            CodegenMethod m = c.addMethod("void writeTo(", MessageWriter.class, " w) throws IOException");
            // m.addAnnotation(Override.class).addJavadoc("{@inheritDoc}");
            m.addImport(IOException.class).addImport(MessageWriter.class);
            for (FieldOrParameter f : fields) {
                StringBuilder sb = new StringBuilder();
                sb.append("w.write").append(new JavaGenType(f.getType()).writeReadName());
                sb.append("(").append(f.getTag()).append(", \"").append(f.getName()).append("\", ");
                sb.append(f.getName());
                if (f.getType().getBaseType() == BaseType.MESSAGE) {
                    sb.append(", ").append(new JavaGenType(f.getType()).render(c) + ".SERIALIZER");
                } else if (f.getType().getBaseType().isComplexType()) {
                    sb.append(", ");
                    if (f.getType() instanceof ListOrSetType) {
                        ListOrSetType lt = (ListOrSetType) f.getType();
                        sb.append(complexParser(c, lt.getElementType()));
                    } else {
                        MapType lt = (MapType) f.getType();
                        sb.append(complexParser(c, lt.getKeyType()));
                        sb.append(", ").append(complexParser(c, lt.getValueType()));
                    }
                }
                sb.append(");");
                m.add(sb);
            }
        }
    }

    void generateAccessors() {
        for (FieldOrParameter f : fields) {
            String beanPrefix = StringUtil.capitalizeFirstLetter(f.getName());
            BaseType t = f.getType().getBaseType();
            JavaGenType los = new JavaGenType(f.getType());
            String r = los.render(c);
            // Getter


            // GETTER
            CodegenMethod get = c.addMethod("public ", r, " get", beanPrefix, "()");
            if (f.getComment().getMain() != null) {
                get.addJavadoc("Returns " + f.getComment().getMainUncapitalized());
            }
            // if (f.getComment() != null && f.getComment().getCommentUncapitalized() != null) {
            // get.addJavadoc("Returns " + f.getComment().getCommentUncapitalized());
            // }
            if (t.isComplexType()) {
                String type = StringUtil.capitalizeFirstLetter(t.name().toLowerCase());
                get.add("return java.util.Collections.unmodifiable", type, "(", f.getName(), ");");
            } else {
                get.add("return ", f.getName(), ";");
            }
            // HAS
            CodegenMethod has = c.addMethod("public boolean has", beanPrefix, "()");
            has.add("return ", f.getName(), " != null;");

            // SETTER
            CodegenMethod set;
            if (t == BaseType.LIST || t == BaseType.SET) {
                JavaGenType element = new JavaGenType(((ListOrSetType) f.getType()).getElementType());
                String name = f.getName();
                set = generateComplexAccessor(c, f);
                set.add("java.util.Objects.requireNonNull(", name, ", \"", name, " is null\");");
                set.add("this.", f.getName(), ".add(", name, ");");
                set.add("return this;");

                String beanPrefix2 = StringUtil.capitalizeFirstLetter(name);


                set = generateComplexAccessorAll(c, f);
                set.add("for (", element.render(c) + " e : ", name, ") {");
                set.add("add", beanPrefix2, "(e);");
                set.add("}");
            } else if (t == BaseType.MAP) {
                set = generateComplexAccessor(c, f);
                set.add("java.util.Objects.requireNonNull(key, \"key is null\");");
                set.add("java.util.Objects.requireNonNull(value, \"value is null\");");
                set.add("this.", f.getName(), ".put(key, value);");
            } else {
                set = c.addMethod("public ", c.getSimpleName(), " set", beanPrefix, "(", r, " ", f.getName(), ")");
                set.add("this.", f.getName(), " = ", f.getName(), ";");
            }

            set.add("return this;");
        }

        // if (f.getComment() != null && f.getComment().getCommentUncapitalized() != null) {
        // m.addJavadoc("Returns " + f.getComment().getCommentUncapitalized());
        // }
        // m.add("return ", f.getName(), ";");
        //
        //


        // public SomeTest putMd(Integer key, Map<List<SomeTest>, String> value) {
        // Map<List<SomeTest>, String> $value = new HashMap<>();
        // for (Map.Entry<List<SomeTest>, String> $e : value.entrySet()) {
        // List<SomeTest> $res = new ArrayList<>();
        // for (SomeTest $st : $e.getKey()) {
        // $res.add(requireNonNull($st));
        // }
        // if (!$res.isEmpty()) {
        // $value.put($res, requireNonNull($e.getValue()));
        // }
        // }
        //
        // return this;
        // }
        //
        // public SomeTest putMpd(Integer key, List<SomeTest> value) {
        // java.util.Objects.requireNonNull(key, "key is null");
        // java.util.Objects.requireNonNull(value, "value is null");
        // List<SomeTest> $res = new ArrayList<>();
        // for (SomeTest $st : value) {
        // $res.add(requireNonNull($st));
        // }
        // if (!$res.isEmpty()) {
        // this.mpd.put(key, $res);
        // } else {
        // this.mpd.remove(key);
        // }
        // return this;
        // }
    }

    CodegenMethod generateComplexAccessor(CodegenClass clazz, FieldOrParameter f) {
        String name = f.getName();
        String beanPrefix2 = StringUtil.capitalizeFirstLetter(f.getName());
        if (f.getType().getBaseType() == BaseType.MAP) {
            JavaGenType key = new JavaGenType(((MapType) f.getType()).getKeyType());
            JavaGenType value = new JavaGenType(((MapType) f.getType()).getValueType());
            return clazz.addMethod("public ", c.getSimpleName(), " put", beanPrefix2, "(", key.render(c), " key, ",
                    value.render(c), " value)");
        } else {
            JavaGenType element = new JavaGenType(((ListOrSetType) f.getType()).getElementType());
            return clazz.addMethod("public ", c.getSimpleName(), " add", beanPrefix2, "(", element.render(c), " ",
                    name, ")");
        }
    }

    CodegenMethod generateComplexAccessorAll(CodegenClass clazz, FieldOrParameter f) {
        String name = f.getName();
        String beanPrefix2 = StringUtil.capitalizeFirstLetter(f.getName());
        if (f.getType().getBaseType() == BaseType.MAP) {
            JavaGenType key = new JavaGenType(((MapType) f.getType()).getKeyType());
            JavaGenType value = new JavaGenType(((MapType) f.getType()).getValueType());
            return clazz.addMethod("public ", c.getSimpleName(), " putAll", beanPrefix2, "(Map<? extends ",
                    key.render(c), ", ? extends ", value.render(c), "> ", name, ")");
        } else {
            clazz.addImport(Collection.class);
            JavaGenType element = new JavaGenType(((ListOrSetType) f.getType()).getElementType());
            return clazz.addMethod("public ", c.getSimpleName(), " addAll", beanPrefix2, "(Collection<? extends ",
                    element.render(c), "> ", name, ")");
        }
    }

    static class NameGenerator {
        private final HashMap<String, Integer> map = new HashMap<>();

        String next(String prefix) {
            Integer val = map.get(prefix);
            map.put(prefix, val == null ? 1 : val + 1);
            return "$" + prefix + (val == null ? "" : val);
        }
    }

    void generateParser() {
        CodegenClass c = this.c.addInnerClass();
        c.setDefinition("static class ", serializerName, " extends ", MessageSerializer.class, "<",
                this.c.getSimpleName(), ">");
        c.addJavadoc("A serializer for reading and writing instances of ", this.c.getSimpleName(), ".");

        // Reader
        CodegenMethod m = c.addMethod("public ", this.c.getSimpleName(), " read(", MessageReader.class,
                " reader) throws ", IOException.class);
        m.addImport(MessageReader.class, IOException.class);
        m.addAnnotation(Override.class).addJavadoc("{@inheritDoc}");
        m.add("return new ", this.c.getSimpleName(), "(" + (fields.isEmpty() ? "" : "reader") + ");");

        // Writer
        m = c.addMethod("public void write(", this.c.getSimpleName(), " message, ", MessageWriter.class,
                " writer) throws ", IOException.class);
        m.addImport(MessageWriter.class);
        m.addAnnotation(Override.class).addJavadoc("{@inheritDoc}");
        if (!fields.isEmpty()) {
            m.add("message.writeTo(writer);");
        }
    }

    static enum MsgType {
        MESSAGE, BROADCAST, ENDPOINT_PARAMETERS;
    }

}
