/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.painless;

import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.index.fielddata.ScriptDocValues;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The entire API for Painless.  Also used as a whitelist for checking for legal
 * methods and fields during at both compile-time and runtime.
 */
public final class Definition {

    /**
     * The default language API to be used with Painless.  The second construction is used
     * to finalize all the variables, so there is no mistake of modification afterwards.
     */
    static Definition INSTANCE = new Definition(new Definition());

    public enum Sort {
        VOID(       void.class      , 0 , true  , false , false , false ),
        BOOL(       boolean.class   , 1 , true  , true  , false , true  ),
        BYTE(       byte.class      , 1 , true  , false , true  , true  ),
        SHORT(      short.class     , 1 , true  , false , true  , true  ),
        CHAR(       char.class      , 1 , true  , false , true  , true  ),
        INT(        int.class       , 1 , true  , false , true  , true  ),
        LONG(       long.class      , 2 , true  , false , true  , true  ),
        FLOAT(      float.class     , 1 , true  , false , true  , true  ),
        DOUBLE(     double.class    , 2 , true  , false , true  , true  ),

        VOID_OBJ(   Void.class      , 1 , true  , false , false , false ),
        BOOL_OBJ(   Boolean.class   , 1 , false , true  , false , false ),
        BYTE_OBJ(   Byte.class      , 1 , false , false , true  , false ),
        SHORT_OBJ(  Short.class     , 1 , false , false , true  , false ),
        CHAR_OBJ(   Character.class , 1 , false , false , true  , false ),
        INT_OBJ(    Integer.class   , 1 , false , false , true  , false ),
        LONG_OBJ(   Long.class      , 1 , false , false , true  , false ),
        FLOAT_OBJ(  Float.class     , 1 , false , false , true  , false ),
        DOUBLE_OBJ( Double.class    , 1 , false , false , true  , false ),

        NUMBER(     Number.class    , 1 , false , false , false , false ),
        STRING(     String.class    , 1 , false , false , false , true  ),

        OBJECT(     null            , 1 , false , false , false , false ),
        DEF(        null            , 1 , false , false , false , false ),
        ARRAY(      null            , 1 , false , false , false , false );

        public final Class<?> clazz;
        public final int size;
        public final boolean primitive;
        public final boolean bool;
        public final boolean numeric;
        public final boolean constant;

        Sort(final Class<?> clazz, final int size, final boolean primitive,
             final boolean bool, final boolean numeric, final boolean constant) {
            this.clazz = clazz;
            this.size = size;
            this.bool = bool;
            this.primitive = primitive;
            this.numeric = numeric;
            this.constant = constant;
        }
    }

    public static final class Type {
        public final String name;
        public final int dimensions;
        public final Struct struct;
        public final Class<?> clazz;
        public final org.objectweb.asm.Type type;
        public final Sort sort;

        private Type(final String name, final int dimensions, final Struct struct,
                     final Class<?> clazz, final org.objectweb.asm.Type type, final Sort sort) {
            this.name = name;
            this.dimensions = dimensions;
            this.struct = struct;
            this.clazz = clazz;
            this.type = type;
            this.sort = sort;
        }

        @Override
        public boolean equals(final Object object) {
            if (this == object) {
                return true;
            }

            if (object == null || getClass() != object.getClass()) {
                return false;
            }

            final Type type = (Type)object;

            return this.type.equals(type.type) && struct.equals(type.struct);
        }

        @Override
        public int hashCode() {
            int result = struct.hashCode();
            result = 31 * result + type.hashCode();

            return result;
        }
    }

    public static final class Constructor {
        public final String name;
        public final Struct owner;
        public final List<Type> arguments;
        public final org.objectweb.asm.commons.Method method;
        public final java.lang.reflect.Constructor<?> reflect;

        private Constructor(final String name, final Struct owner, final List<Type> arguments,
                            final org.objectweb.asm.commons.Method method, final java.lang.reflect.Constructor<?> reflect) {
            this.name = name;
            this.owner = owner;
            this.arguments = Collections.unmodifiableList(arguments);
            this.method = method;
            this.reflect = reflect;
        }
    }

    public static class Method {
        public final String name;
        public final Struct owner;
        public final Type rtn;
        public final List<Type> arguments;
        public final org.objectweb.asm.commons.Method method;
        public final java.lang.reflect.Method reflect;
        public final MethodHandle handle;

        private Method(final String name, final Struct owner, final Type rtn, final List<Type> arguments,
                       final org.objectweb.asm.commons.Method method, final java.lang.reflect.Method reflect,
                       final MethodHandle handle) {
            this.name = name;
            this.owner = owner;
            this.rtn = rtn;
            this.arguments = Collections.unmodifiableList(arguments);
            this.method = method;
            this.reflect = reflect;
            this.handle = handle;
        }
    }

    public static final class Field {
        public final String name;
        public final Struct owner;
        public final Type generic;
        public final Type type;
        public final java.lang.reflect.Field reflect;
        public final MethodHandle getter;
        public final MethodHandle setter;

        private Field(final String name, final Struct owner, final Type generic, final Type type,
                      final java.lang.reflect.Field reflect, final MethodHandle getter, final MethodHandle setter) {
            this.name = name;
            this.owner = owner;
            this.generic = generic;
            this.type = type;
            this.reflect = reflect;
            this.getter = getter;
            this.setter = setter;
        }
    }

    public static final class Struct {
        public final String name;
        public final Class<?> clazz;
        public final org.objectweb.asm.Type type;

        public final Map<String, Constructor> constructors;
        public final Map<String, Method> functions;
        public final Map<String, Method> methods;

        public final Map<String, Field> statics;
        public final Map<String, Field> members;

        private Struct(final String name, final Class<?> clazz, final org.objectweb.asm.Type type) {
            this.name = name;
            this.clazz = clazz;
            this.type = type;

            constructors = new HashMap<>();
            functions = new HashMap<>();
            methods = new HashMap<>();

            statics = new HashMap<>();
            members = new HashMap<>();
        }

        private Struct(final Struct struct) {
            name = struct.name;
            clazz = struct.clazz;
            type = struct.type;

            constructors = Collections.unmodifiableMap(struct.constructors);
            functions = Collections.unmodifiableMap(struct.functions);
            methods = Collections.unmodifiableMap(struct.methods);

            statics = Collections.unmodifiableMap(struct.statics);
            members = Collections.unmodifiableMap(struct.members);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }

            if (object == null || getClass() != object.getClass()) {
                return false;
            }

            Struct struct = (Struct)object;

            return name.equals(struct.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    public static class Cast {
        public final Type from;
        public final Type to;

        public Cast(final Type from, final Type to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(final Object object) {
            if (this == object) {
                return true;
            }

            if (object == null || getClass() != object.getClass()) {
                return false;
            }

            final Cast cast = (Cast)object;

            return from.equals(cast.from) && to.equals(cast.to);
        }

        @Override
        public int hashCode() {
            int result = from.hashCode();
            result = 31 * result + to.hashCode();

            return result;
        }
    }

    public static final class Transform extends Cast {
        public final Cast cast;
        public final Method method;
        public final Type upcast;
        public final Type downcast;

        public Transform(final Cast cast, Method method, final Type upcast, final Type downcast) {
            super(cast.from, cast.to);

            this.cast = cast;
            this.method = method;
            this.upcast = upcast;
            this.downcast = downcast;
        }
    }

    public static final class RuntimeClass {
        public final Map<String, Method> methods;
        public final Map<String, MethodHandle> getters;
        public final Map<String, MethodHandle> setters;

        private RuntimeClass(final Map<String, Method> methods,
                             final Map<String, MethodHandle> getters, final Map<String, MethodHandle> setters) {
            this.methods = methods;
            this.getters = getters;
            this.setters = setters;
        }
    }

    public final Map<String, Struct> structsMap;
    public final Map<Cast, Transform> transformsMap;
    public final Map<Class<?>, RuntimeClass> runtimeMap;

    public final Type voidType;
    public final Type booleanType;
    public final Type byteType;
    public final Type shortType;
    public final Type charType;
    public final Type intType;
    public final Type longType;
    public final Type floatType;
    public final Type doubleType;

    public final Type voidobjType;
    public final Type booleanobjType;
    public final Type byteobjType;
    public final Type shortobjType;
    public final Type charobjType;
    public final Type intobjType;
    public final Type longobjType;
    public final Type floatobjType;
    public final Type doubleobjType;

    public final Type objectType;
    public final Type defType;
    public final Type numberType;
    public final Type charseqType;
    public final Type stringType;
    public final Type mathType;
    public final Type utilityType;
    public final Type defobjType;

    public final Type itrType;
    public final Type oitrType;
    public final Type sitrType;

    public final Type collectionType;
    public final Type ocollectionType;
    public final Type scollectionType;

    public final Type listType;
    public final Type arraylistType;
    public final Type olistType;
    public final Type oarraylistType;
    public final Type slistType;
    public final Type sarraylistType;

    public final Type setType;
    public final Type hashsetType;
    public final Type osetType;
    public final Type ohashsetType;
    public final Type ssetType;
    public final Type shashsetType;

    public final Type mapType;
    public final Type hashmapType;
    public final Type oomapType;
    public final Type oohashmapType;
    public final Type smapType;
    public final Type shashmapType;
    public final Type somapType;
    public final Type sohashmapType;

    public final Type execType;

    public final Type exceptionType;
    public final Type arithexcepType;
    public final Type iargexcepType;
    public final Type istateexceptType;
    public final Type nfexcepType;

    // docvalues accessors
    public final Type geoPointType;
    public final Type stringsType;
    // TODO: add ReadableDateTime? or don't expose the joda stuff?
    public final Type longsType;
    public final Type doublesType;
    public final Type geoPointsType;

    private Definition() {
        structsMap = new HashMap<>();
        transformsMap = new HashMap<>();
        runtimeMap = new HashMap<>();

        addStructs();

        voidType = getType("void");
        booleanType = getType("boolean");
        byteType = getType("byte");
        shortType = getType("short");
        charType = getType("char");
        intType = getType("int");
        longType = getType("long");
        floatType = getType("float");
        doubleType = getType("double");

        voidobjType = getType("Void");
        booleanobjType = getType("Boolean");
        byteobjType = getType("Byte");
        shortobjType = getType("Short");
        charobjType = getType("Character");
        intobjType = getType("Integer");
        longobjType = getType("Long");
        floatobjType = getType("Float");
        doubleobjType = getType("Double");

        objectType = getType("Object");
        defType = getType("def");
        numberType = getType("Number");
        charseqType = getType("CharSequence");
        stringType = getType("String");
        mathType = getType("Math");
        utilityType = getType("Utility");
        defobjType = getType("Def");

        itrType = getType("Iterator");
        oitrType = getType("Iterator<Object>");
        sitrType = getType("Iterator<String>");

        collectionType = getType("Collection");
        ocollectionType = getType("Collection<Object>");
        scollectionType = getType("Collection<String>");

        listType = getType("List");
        arraylistType = getType("ArrayList");
        olistType = getType("List<Object>");
        oarraylistType = getType("ArrayList<Object>");
        slistType = getType("List<String>");
        sarraylistType = getType("ArrayList<String>");

        setType = getType("Set");
        hashsetType = getType("HashSet");
        osetType = getType("Set<Object>");
        ohashsetType = getType("HashSet<Object>");
        ssetType = getType("Set<String>");
        shashsetType = getType("HashSet<String>");

        mapType = getType("Map");
        hashmapType = getType("HashMap");
        oomapType = getType("Map<Object,Object>");
        oohashmapType = getType("HashMap<Object,Object>");
        smapType = getType("Map<String,def>");
        shashmapType = getType("HashMap<String,def>");
        somapType = getType("Map<String,Object>");
        sohashmapType = getType("HashMap<String,Object>");

        execType = getType("Executable");

        exceptionType = getType("Exception");
        arithexcepType = getType("ArithmeticException");
        iargexcepType = getType("IllegalArgumentException");
        istateexceptType = getType("IllegalStateException");
        nfexcepType = getType("NumberFormatException");

        geoPointType = getType("GeoPoint");
        stringsType = getType("Strings");
        longsType = getType("Longs");
        doublesType = getType("Doubles");
        geoPointsType = getType("GeoPoints");

        addElements();
        copyStructs();
        addTransforms();
        addRuntimeClasses();
    }

    private Definition(final Definition definition) {
        final Map<String, Struct> structs = new HashMap<>();

        for (final Struct struct : definition.structsMap.values()) {
            structs.put(struct.name, new Struct(struct));
        }

        this.structsMap = Collections.unmodifiableMap(structs);
        this.transformsMap = Collections.unmodifiableMap(definition.transformsMap);
        this.runtimeMap = Collections.unmodifiableMap(definition.runtimeMap);

        this.voidType = definition.voidType;
        this.booleanType = definition.booleanType;
        this.byteType = definition.byteType;
        this.shortType = definition.shortType;
        this.charType = definition.charType;
        this.intType = definition.intType;
        this.longType = definition.longType;
        this.floatType = definition.floatType;
        this.doubleType = definition.doubleType;

        this.voidobjType = definition.voidobjType;
        this.booleanobjType = definition.booleanobjType;
        this.byteobjType = definition.byteobjType;
        this.shortobjType = definition.shortobjType;
        this.charobjType = definition.charobjType;
        this.intobjType = definition.intobjType;
        this.longobjType = definition.longobjType;
        this.floatobjType = definition.floatobjType;
        this.doubleobjType = definition.doubleobjType;

        this.objectType = definition.objectType;
        this.defType = definition.defType;
        this.numberType = definition.numberType;
        this.charseqType = definition.charseqType;
        this.stringType = definition.stringType;
        this.mathType = definition.mathType;
        this.utilityType = definition.utilityType;
        this.defobjType = definition.defobjType;

        this.itrType = definition.itrType;
        this.oitrType = definition.oitrType;
        this.sitrType = definition.sitrType;

        this.collectionType = definition.collectionType;
        this.ocollectionType = definition.ocollectionType;
        this.scollectionType = definition.scollectionType;

        this.listType = definition.listType;
        this.arraylistType = definition.arraylistType;
        this.olistType = definition.olistType;
        this.oarraylistType = definition.oarraylistType;
        this.slistType = definition.slistType;
        this.sarraylistType = definition.sarraylistType;

        this.setType = definition.setType;
        this.hashsetType = definition.hashsetType;
        this.osetType = definition.osetType;
        this.ohashsetType = definition.ohashsetType;
        this.ssetType = definition.ssetType;
        this.shashsetType = definition.shashsetType;

        this.mapType = definition.mapType;
        this.hashmapType = definition.hashmapType;
        this.oomapType = definition.oomapType;
        this.oohashmapType = definition.oohashmapType;
        this.smapType = definition.smapType;
        this.shashmapType = definition.shashmapType;
        this.somapType = definition.somapType;
        this.sohashmapType = definition.sohashmapType;

        this.execType = definition.execType;

        this.exceptionType = definition.exceptionType;
        this.arithexcepType = definition.arithexcepType;
        this.iargexcepType = definition.iargexcepType;
        this.istateexceptType = definition.istateexceptType;
        this.nfexcepType = definition.nfexcepType;

        this.geoPointType = definition.geoPointType;
        this.stringsType = definition.stringsType;
        this.longsType = definition.longsType;
        this.doublesType = definition.doublesType;
        this.geoPointsType = definition.geoPointsType;
    }

    private void addStructs() {
        addStruct( "void"    , void.class    );
        addStruct( "boolean" , boolean.class );
        addStruct( "byte"    , byte.class    );
        addStruct( "short"   , short.class   );
        addStruct( "char"    , char.class    );
        addStruct( "int"     , int.class     );
        addStruct( "long"    , long.class    );
        addStruct( "float"   , float.class   );
        addStruct( "double"  , double.class  );

        addStruct( "Void"      , Void.class      );
        addStruct( "Boolean"   , Boolean.class   );
        addStruct( "Byte"      , Byte.class      );
        addStruct( "Short"     , Short.class     );
        addStruct( "Character" , Character.class );
        addStruct( "Integer"   , Integer.class   );
        addStruct( "Long"      , Long.class      );
        addStruct( "Float"     , Float.class     );
        addStruct( "Double"    , Double.class    );

        addStruct( "Object"       , Object.class       );
        addStruct( "def"          , Object.class       );
        addStruct( "Number"       , Number.class       );
        addStruct( "CharSequence" , CharSequence.class );
        addStruct( "String"       , String.class       );
        addStruct( "Math"         , Math.class         );
        addStruct( "Utility"      , Utility.class      );
        addStruct( "Def"          , Def.class          );

        addStruct( "Iterator"         , Iterator.class );
        addStruct( "Iterator<Object>" , Iterator.class );
        addStruct( "Iterator<String>" , Iterator.class );

        addStruct( "Collection"         , Collection.class );
        addStruct( "Collection<Object>" , Collection.class );
        addStruct( "Collection<String>" , Collection.class );

        addStruct( "List"              , List.class      );
        addStruct( "ArrayList"         , ArrayList.class );
        addStruct( "List<Object>"      , List.class      );
        addStruct( "ArrayList<Object>" , ArrayList.class );
        addStruct( "List<String>"      , List.class      );
        addStruct( "ArrayList<String>" , ArrayList.class );

        addStruct( "Set"             , Set.class     );
        addStruct( "HashSet"         , HashSet.class );
        addStruct( "Set<Object>"     , Set.class     );
        addStruct( "HashSet<Object>" , HashSet.class );
        addStruct( "Set<String>"     , Set.class     );
        addStruct( "HashSet<String>" , HashSet.class );

        addStruct( "Map"                    , Map.class     );
        addStruct( "HashMap"                , HashMap.class );
        addStruct( "Map<Object,Object>"     , Map.class     );
        addStruct( "HashMap<Object,Object>" , HashMap.class );
        addStruct( "Map<String,def>"        , Map.class     );
        addStruct( "HashMap<String,def>"    , HashMap.class );
        addStruct( "Map<String,Object>"     , Map.class     );
        addStruct( "HashMap<String,Object>" , HashMap.class );

        addStruct( "Executable" , Executable.class );

        addStruct( "Exception"                , Exception.class);
        addStruct( "ArithmeticException"      , ArithmeticException.class);
        addStruct( "IllegalArgumentException" , IllegalArgumentException.class);
        addStruct( "IllegalStateException"    , IllegalStateException.class);
        addStruct( "NumberFormatException"    , NumberFormatException.class);

        addStruct( "GeoPoint"  , GeoPoint.class);
        addStruct( "Strings"   , ScriptDocValues.Strings.class);
        addStruct( "Longs"     , ScriptDocValues.Longs.class);
        addStruct( "Doubles"   , ScriptDocValues.Doubles.class);
        addStruct( "GeoPoints" , ScriptDocValues.GeoPoints.class);
    }

    private void addElements() {
        addMethod("Object", "toString", null, false, stringType, new Type[] {}, null, null);
        addMethod("Object", "equals", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Object", "hashCode", null, false, intType, new Type[] {}, null, null);

        addMethod("def", "toString", null, false, stringType, new Type[] {}, null, null);
        addMethod("def", "equals", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("def", "hashCode", null, false, intType, new Type[] {}, null, null);

        addConstructor("Boolean", "new", new Type[] {booleanType}, null);
        addMethod("Boolean", "valueOf", null, true, booleanobjType, new Type[] {booleanType}, null, null);
        addMethod("Boolean", "booleanValue", null, false, booleanType, new Type[] {}, null, null);

        addConstructor("Byte", "new", new Type[] {byteType}, null);
        addMethod("Byte", "valueOf", null, true, byteobjType, new Type[] {byteType}, null, null);
        addField("Byte", "MIN_VALUE", null, true, byteType, null);
        addField("Byte", "MAX_VALUE", null, true, byteType, null);

        addConstructor("Short", "new", new Type[] {shortType}, null);
        addMethod("Short", "valueOf", null, true, shortobjType, new Type[] {shortType}, null, null);
        addField("Short", "MIN_VALUE", null, true, shortType, null);
        addField("Short", "MAX_VALUE", null, true, shortType, null);

        addConstructor("Character", "new", new Type[] {charType}, null);
        addMethod("Character", "valueOf", null, true, charobjType, new Type[] {charType}, null, null);
        addMethod("Character", "charValue", null, false, charType, new Type[] {}, null, null);
        addField("Character", "MIN_VALUE", null, true, charType, null);
        addField("Character", "MAX_VALUE", null, true, charType, null);

        addConstructor("Integer", "new", new Type[] {intType}, null);
        addMethod("Integer", "valueOf", null, true, intobjType, new Type[] {intType}, null, null);
        addField("Integer", "MIN_VALUE", null, true, intType, null);
        addField("Integer", "MAX_VALUE", null, true, intType, null);

        addConstructor("Long", "new", new Type[] {longType}, null);
        addMethod("Long", "valueOf", null, true, longobjType, new Type[] {longType}, null, null);
        addField("Long", "MIN_VALUE", null, true, longType, null);
        addField("Long", "MAX_VALUE", null, true, longType, null);

        addConstructor("Float", "new", new Type[] {floatType}, null);
        addMethod("Float", "valueOf", null, true, floatobjType, new Type[] {floatType}, null, null);
        addField("Float", "MIN_VALUE", null, true, floatType, null);
        addField("Float", "MAX_VALUE", null, true, floatType, null);

        addConstructor("Double", "new", new Type[] {doubleType}, null);
        addMethod("Double", "valueOf", null, true, doubleobjType, new Type[] {doubleType}, null, null);
        addField("Double", "MIN_VALUE", null, true, doubleType, null);
        addField("Double", "MAX_VALUE", null, true, doubleType, null);

        addMethod("Number", "byteValue", null, false, byteType, new Type[] {}, null, null);
        addMethod("Number", "shortValue", null, false, shortType, new Type[] {}, null, null);
        addMethod("Number", "intValue", null, false, intType, new Type[] {}, null, null);
        addMethod("Number", "longValue", null, false, longType, new Type[] {}, null, null);
        addMethod("Number", "floatValue", null, false, floatType, new Type[] {}, null, null);
        addMethod("Number", "doubleValue", null, false, doubleType, new Type[] {}, null, null);

        addMethod("CharSequence", "charAt", null, false, charType, new Type[] {intType}, null, null);
        addMethod("CharSequence", "length", null, false, intType, new Type[] {}, null, null);

        addConstructor("String", "new", new Type[] {}, null);
        addMethod("String", "codePointAt", null, false, intType, new Type[] {intType}, null, null);
        addMethod("String", "compareTo", null, false, intType, new Type[] {stringType}, null, null);
        addMethod("String", "concat", null, false, stringType, new Type[] {stringType}, null, null);
        addMethod("String", "endsWith", null, false, booleanType, new Type[] {stringType}, null, null);
        addMethod("String", "indexOf", null, false, intType, new Type[] {stringType, intType}, null, null);
        addMethod("String", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("String", "replace", null, false, stringType, new Type[] {charseqType, charseqType}, null, null);
        addMethod("String", "startsWith", null, false, booleanType, new Type[] {stringType}, null, null);
        addMethod("String", "substring", null, false, stringType, new Type[] {intType, intType}, null, null);
        addMethod("String", "toCharArray", null, false, getType(charType.struct, 1), new Type[] {}, null, null);
        addMethod("String", "trim", null, false, stringType, new Type[] {}, null, null);

        addMethod("Utility", "NumberToboolean", null, true, booleanType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberTochar", null, true, charType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToBoolean", null, true, booleanobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToByte", null, true, byteobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToShort", null, true, shortobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToCharacter", null, true, charobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToInteger", null, true, intobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToLong", null, true, longobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToFloat", null, true, floatobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToDouble", null, true, doubleobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "booleanTobyte", null, true, byteType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanToshort", null, true, shortType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanTochar", null, true, charType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanToint", null, true, intType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanTolong", null, true, longType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanTofloat", null, true, floatType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanTodouble", null, true, doubleType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanToInteger", null, true, intobjType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "BooleanTobyte", null, true, byteType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToshort", null, true, shortType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanTochar", null, true, charType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToint", null, true, intType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanTolong", null, true, longType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanTofloat", null, true, floatType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanTodouble", null, true, doubleType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToByte", null, true, byteobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToShort", null, true, shortobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToCharacter", null, true, charobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToInteger", null, true, intobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToLong", null, true, longobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToFloat", null, true, floatobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToDouble", null, true, doubleobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "byteToboolean", null, true, booleanType, new Type[] {byteType}, null, null);
        addMethod("Utility", "byteToShort", null, true, shortobjType, new Type[] {byteType}, null, null);
        addMethod("Utility", "byteToCharacter", null, true, charobjType, new Type[] {byteType}, null, null);
        addMethod("Utility", "byteToInteger", null, true, intobjType, new Type[] {byteType}, null, null);
        addMethod("Utility", "byteToLong", null, true, longobjType, new Type[] {byteType}, null, null);
        addMethod("Utility", "byteToFloat", null, true, floatobjType, new Type[] {byteType}, null, null);
        addMethod("Utility", "byteToDouble", null, true, doubleobjType, new Type[] {byteType}, null, null);
        addMethod("Utility", "ByteToboolean", null, true, booleanType, new Type[] {byteobjType}, null, null);
        addMethod("Utility", "ByteTochar", null, true, charType, new Type[] {byteobjType}, null, null);
        addMethod("Utility", "shortToboolean", null, true, booleanType, new Type[] {shortType}, null, null);
        addMethod("Utility", "shortToByte", null, true, byteobjType, new Type[] {shortType}, null, null);
        addMethod("Utility", "shortToCharacter", null, true, charobjType, new Type[] {shortType}, null, null);
        addMethod("Utility", "shortToInteger", null, true, intobjType, new Type[] {shortType}, null, null);
        addMethod("Utility", "shortToLong", null, true, longobjType, new Type[] {shortType}, null, null);
        addMethod("Utility", "shortToFloat", null, true, floatobjType, new Type[] {shortType}, null, null);
        addMethod("Utility", "shortToDouble", null, true, doubleobjType, new Type[] {shortType}, null, null);
        addMethod("Utility", "ShortToboolean", null, true, booleanType, new Type[] {shortobjType}, null, null);
        addMethod("Utility", "ShortTochar", null, true, charType, new Type[] {shortobjType}, null, null);
        addMethod("Utility", "charToboolean", null, true, booleanType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToByte", null, true, byteobjType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToShort", null, true, shortobjType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToInteger", null, true, intobjType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToLong", null, true, longobjType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToFloat", null, true, floatobjType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToDouble", null, true, doubleobjType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToString", null, true, stringType, new Type[] {charType}, null, null);
        addMethod("Utility", "CharacterToboolean", null, true, booleanType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterTobyte", null, true, byteType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToshort", null, true, shortType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToint", null, true, intType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterTolong", null, true, longType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterTofloat", null, true, floatType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterTodouble", null, true, doubleType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToBoolean", null, true, booleanobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToByte", null, true, byteobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToShort", null, true, shortobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToInteger", null, true, intobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToLong", null, true, longobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToFloat", null, true, floatobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToDouble", null, true, doubleobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToString", null, true, stringType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "intToboolean", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Utility", "intToByte", null, true, byteobjType, new Type[] {intType}, null, null);
        addMethod("Utility", "intToShort", null, true, shortobjType, new Type[] {intType}, null, null);
        addMethod("Utility", "intToCharacter", null, true, charobjType, new Type[] {intType}, null, null);
        addMethod("Utility", "intToLong", null, true, longobjType, new Type[] {intType}, null, null);
        addMethod("Utility", "intToFloat", null, true, floatobjType, new Type[] {intType}, null, null);
        addMethod("Utility", "intToDouble", null, true, doubleobjType, new Type[] {intType}, null, null);
        addMethod("Utility", "IntegerToboolean", null, true, booleanType, new Type[] {intobjType}, null, null);
        addMethod("Utility", "IntegerTochar", null, true, charType, new Type[] {intobjType}, null, null);
        addMethod("Utility", "longToboolean", null, true, booleanType, new Type[] {longType}, null, null);
        addMethod("Utility", "longToByte", null, true, byteobjType, new Type[] {longType}, null, null);
        addMethod("Utility", "longToShort", null, true, shortobjType, new Type[] {longType}, null, null);
        addMethod("Utility", "longToCharacter", null, true, charobjType, new Type[] {longType}, null, null);
        addMethod("Utility", "longToInteger", null, true, intobjType, new Type[] {longType}, null, null);
        addMethod("Utility", "longToFloat", null, true, floatobjType, new Type[] {longType}, null, null);
        addMethod("Utility", "longToDouble", null, true, doubleobjType, new Type[] {longType}, null, null);
        addMethod("Utility", "LongToboolean", null, true, booleanType, new Type[] {longobjType}, null, null);
        addMethod("Utility", "LongTochar", null, true, charType, new Type[] {longobjType}, null, null);
        addMethod("Utility", "floatToboolean", null, true, booleanType, new Type[] {floatType}, null, null);
        addMethod("Utility", "floatToByte", null, true, byteobjType, new Type[] {floatType}, null, null);
        addMethod("Utility", "floatToShort", null, true, shortobjType, new Type[] {floatType}, null, null);
        addMethod("Utility", "floatToCharacter", null, true, charobjType, new Type[] {floatType}, null, null);
        addMethod("Utility", "floatToInteger", null, true, intobjType, new Type[] {floatType}, null, null);
        addMethod("Utility", "floatToLong", null, true, longobjType, new Type[] {floatType}, null, null);
        addMethod("Utility", "floatToDouble", null, true, doubleobjType, new Type[] {floatType}, null, null);
        addMethod("Utility", "FloatToboolean", null, true, booleanType, new Type[] {floatobjType}, null, null);
        addMethod("Utility", "FloatTochar", null, true, charType, new Type[] {floatobjType}, null, null);
        addMethod("Utility", "doubleToboolean", null, true, booleanType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "doubleToByte", null, true, byteobjType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "doubleToShort", null, true, shortobjType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "doubleToCharacter", null, true, charobjType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "doubleToInteger", null, true, intobjType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "doubleToLong", null, true, longobjType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "doubleToFloat", null, true, floatobjType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "DoubleToboolean", null, true, booleanType, new Type[] {doubleobjType}, null, null);
        addMethod("Utility", "DoubleTochar", null, true, charType, new Type[] {doubleobjType}, null, null);
        addMethod("Utility", "StringTochar", null, true, charType, new Type[] {stringType}, null, null);
        addMethod("Utility", "StringToCharacter", null, true, charobjType, new Type[] {stringType}, null, null);

        addMethod("Math", "abs", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "fabs", "abs", true, floatType, new Type[] {floatType}, null, null);
        addMethod("Math", "labs", "abs", true, longType, new Type[] {longType}, null, null);
        addMethod("Math", "iabs", "abs", true, intType, new Type[] {intType}, null, null);
        addMethod("Math", "acos", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "asin", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "atan", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "atan2", null, true, doubleType, new Type[] {doubleType, doubleType}, null, null);
        addMethod("Math", "cbrt", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "ceil", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "cos", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "cosh", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "exp", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "expm1", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "floor", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "hypot", null, true, doubleType, new Type[] {doubleType, doubleType}, null, null);
        addMethod("Math", "log", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "log10", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "log1p", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "max", "max", true, doubleType, new Type[] {doubleType, doubleType}, null, null);
        addMethod("Math", "fmax", "max", true, floatType, new Type[] {floatType, floatType}, null, null);
        addMethod("Math", "lmax", "max", true, longType, new Type[] {longType, longType}, null, null);
        addMethod("Math", "imax", "max", true, intType, new Type[] {intType, intType}, null, null);
        addMethod("Math", "min", "min", true, doubleType, new Type[] {doubleType, doubleType}, null, null);
        addMethod("Math", "fmin", "min", true, floatType, new Type[] {floatType, floatType}, null, null);
        addMethod("Math", "lmin", "min", true, longType, new Type[] {longType, longType}, null, null);
        addMethod("Math", "imin", "min", true, intType, new Type[] {intType, intType}, null, null);
        addMethod("Math", "pow", null, true, doubleType, new Type[] {doubleType, doubleType}, null, null);
        addMethod("Math", "random", null, true, doubleType, new Type[] {}, null, null);
        addMethod("Math", "rint", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "round", null, true, longType, new Type[] {doubleType}, null, null);
        addMethod("Math", "sin", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "sinh", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "sqrt", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "tan", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "tanh", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "toDegrees", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "toRadians", null, true, doubleType, new Type[] {doubleType}, null, null);

        addMethod("Def", "DefToboolean", null, true, booleanType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTobyte", null, true, byteType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToshort", null, true, shortType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTochar", null, true, charType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToint", null, true, intType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTolong", null, true, longType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTofloat", null, true, floatType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTodouble", null, true, doubleType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToBoolean", null, true, booleanobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToByte", null, true, byteobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToShort", null, true, shortobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToCharacter", null, true, charobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToInteger", null, true, intobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToLong", null, true, longobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToFloat", null, true, floatobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToDouble", null, true, doubleobjType, new Type[] {defType}, null, null);

        addMethod("Iterator", "hasNext", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Iterator", "next", null, false, objectType, new Type[] {}, defType, null);
        addMethod("Iterator", "remove", null, false, voidType, new Type[] {}, null, null);

        addMethod("Iterator<Object>", "hasNext", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Iterator<Object>", "next", null, false, objectType, new Type[] {}, null, null);
        addMethod("Iterator<Object>", "remove", null, false, voidType, new Type[] {}, null, null);

        addMethod("Iterator<String>", "hasNext", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Iterator<String>", "next", null, false, objectType, new Type[] {}, stringType, null);
        addMethod("Iterator<String>", "remove", null, false, voidType, new Type[] {}, null, null);

        addMethod("Collection", "add", null, false, booleanType, new Type[] {objectType}, null, new Type[] {defType});
        addMethod("Collection", "clear", null, false, voidType, new Type[] {}, null, null);
        addMethod("Collection", "contains", null, false, booleanType, new Type[] {objectType}, null, new Type[] {defType});
        addMethod("Collection", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Collection", "iterator", null, false, itrType, new Type[] {}, null, null);
        addMethod("Collection", "remove", null, false, booleanType, new Type[] {objectType}, null, new Type[] {defType});
        addMethod("Collection", "size", null, false, intType, new Type[] {}, null, null);

        addMethod("Collection<Object>", "add", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Collection<Object>", "clear", null, false, voidType, new Type[] {}, null, null);
        addMethod("Collection<Object>", "contains", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Collection<Object>", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Collection<Object>", "iterator", null, false, oitrType, new Type[] {}, null, null);
        addMethod("Collection<Object>", "remove", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Collection<Object>", "size", null, false, intType, new Type[] {}, null, null);

        addMethod("Collection<String>", "add", null, false, booleanType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Collection<String>", "clear", null, false, voidType, new Type[] {}, null, null);
        addMethod("Collection<String>", "contains", null, false, booleanType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Collection<String>", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Collection<String>", "iterator", null, false, sitrType, new Type[] {}, null, null);
        addMethod("Collection<String>", "remove", null, false, booleanType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Collection<String>", "size", null, false, intType, new Type[] {}, null, null);

        addMethod("List", "set", null, false, objectType, new Type[] {intType, objectType}, defType, new Type[] {intType, defType});
        addMethod("List", "get", null, false, objectType, new Type[] {intType}, defType, null);
        addMethod("List", "remove", null, false, objectType, new Type[] {intType}, defType, null);
        addMethod("List", "getLength", "size", false, intType, new Type[] {}, null, null);

        addConstructor("ArrayList", "new", new Type[] {}, null);

        addMethod("List<Object>", "set", null, false, objectType, new Type[] {intType, objectType}, null, null);
        addMethod("List<Object>", "get", null, false, objectType, new Type[] {intType}, null, null);
        addMethod("List<Object>", "remove", null, false, objectType, new Type[] {intType}, null, null);
        addMethod("List<Object>", "getLength", "size", false, intType, new Type[] {}, null, null);

        addConstructor("ArrayList<Object>", "new", new Type[] {}, null);

        addMethod("List<String>", "set", null, false, objectType, new Type[] {intType, objectType}, stringType,
            new Type[] {intType, stringType});
        addMethod("List<String>", "get", null, false, objectType, new Type[] {intType}, stringType, null);
        addMethod("List<String>", "remove", null, false, objectType, new Type[] {intType}, stringType, null);
        addMethod("List<String>", "getLength", "size", false, intType, new Type[] {}, null, null);

        addConstructor("ArrayList<String>", "new", new Type[] {}, null);

        addConstructor("HashSet", "new", new Type[] {}, null);

        addConstructor("HashSet<Object>", "new", new Type[] {}, null);

        addConstructor("HashSet<String>", "new", new Type[] {}, null);

        addMethod("Map", "put", null, false, objectType, new Type[] {objectType, objectType}, defType, new Type[] {defType, defType});
        addMethod("Map", "get", null, false, objectType, new Type[] {objectType}, defType, new Type[] {defType});
        addMethod("Map", "remove", null, false, objectType, new Type[] {objectType}, null, null);
        addMethod("Map", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Map", "size", null, false, intType, new Type[] {}, null, null);
        addMethod("Map", "containsKey", null, false, booleanType, new Type[] {objectType}, null, new Type[] {defType});
        addMethod("Map", "containsValue", null, false, booleanType, new Type[] {objectType}, null, new Type[] {defType});
        addMethod("Map", "keySet", null, false, osetType, new Type[] {}, setType, null);
        addMethod("Map", "values", null, false, ocollectionType, new Type[] {}, collectionType, null);

        addConstructor("HashMap", "new", new Type[] {}, null);

        addMethod("Map<Object,Object>", "put", null, false, objectType, new Type[] {objectType, objectType}, null, null);
        addMethod("Map<Object,Object>", "get", null, false, objectType, new Type[] {objectType}, null, null);
        addMethod("Map<Object,Object>", "remove", null, false, objectType, new Type[] {objectType}, null, null);
        addMethod("Map<Object,Object>", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Map<Object,Object>", "size", null, false, intType, new Type[] {}, null, null);
        addMethod("Map<Object,Object>", "containsKey", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Map<Object,Object>", "containsValue", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Map<Object,Object>", "keySet", null, false, osetType, new Type[] {}, null, null);
        addMethod("Map<Object,Object>", "values", null, false, ocollectionType, new Type[] {}, null, null);

        addConstructor("HashMap<Object,Object>", "new", new Type[] {}, null);

        addMethod("Map<String,def>", "put", null, false, objectType, new Type[] {objectType, objectType}, defType,
            new Type[] {stringType, defType});
        addMethod("Map<String,def>", "get", null, false, objectType, new Type[] {objectType}, defType, new Type[] {stringType});
        addMethod("Map<String,def>", "remove", null, false, objectType, new Type[] {objectType}, defType, new Type[] {stringType});
        addMethod("Map<String,def>", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Map<String,def>", "size", null, false, intType, new Type[] {}, null, null);
        addMethod("Map<String,def>", "containsKey", null, false, booleanType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Map<String,def>", "containsValue", null, false, booleanType, new Type[] {objectType}, null, new Type[] {defType});
        addMethod("Map<String,def>", "keySet", null, false, osetType, new Type[] {}, ssetType, null);
        addMethod("Map<String,def>", "values", null, false, ocollectionType, new Type[] {}, collectionType, null);

        addConstructor("HashMap<String,def>", "new", new Type[] {}, null);

        addMethod("Map<String,Object>", "put", null, false, objectType, new Type[] {objectType, objectType}, null,
            new Type[] {stringType, objectType});
        addMethod("Map<String,Object>", "get", null, false, objectType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Map<String,Object>", "remove", null, false, objectType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Map<String,Object>", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Map<String,Object>", "size", null, false, intType, new Type[] {}, null, null);
        addMethod("Map<String,Object>", "containsKey", null, false, booleanType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Map<String,Object>", "containsValue", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Map<String,Object>", "keySet", null, false, osetType, new Type[] {}, ssetType, null);
        addMethod("Map<String,Object>", "values", null, false, ocollectionType, new Type[] {}, null, null);

        addConstructor("HashMap<String,Object>", "new", new Type[] {}, null);

        addMethod("Exception", "getMessage", null, false, stringType, new Type[] {}, null, null);

        addConstructor("ArithmeticException", "new", new Type[] {stringType}, null);

        addConstructor("IllegalArgumentException", "new", new Type[] {stringType}, null);

        addConstructor("IllegalStateException", "new", new Type[] {stringType}, null);

        addConstructor("NumberFormatException", "new", new Type[] {stringType}, null);

        addMethod("GeoPoint", "getLat", null, false, doubleType, new Type[] {}, null, null);
        addMethod("GeoPoint", "getLon", null, false, doubleType, new Type[] {}, null, null);
        addMethod("Strings", "getValue", null, false, stringType, new Type[] {}, null, null);
        addMethod("Strings", "getValues", null, false, slistType, new Type[] {}, null, null);
        addMethod("Longs", "getValue", null, false, longType, new Type[] {}, null, null);
        addMethod("Longs", "getValues", null, false, olistType, new Type[] {}, null, null);
        // TODO: add better date support for Longs here? (carefully?)
        addMethod("Doubles", "getValue", null, false, doubleType, new Type[] {}, null, null);
        addMethod("Doubles", "getValues", null, false, olistType, new Type[] {}, null, null);
        addMethod("GeoPoints", "getValue", null, false, geoPointType, new Type[] {}, null, null);
        addMethod("GeoPoints", "getValues", null, false, olistType, new Type[] {}, null, null);
        addMethod("GeoPoints", "getLat", null, false, doubleType, new Type[] {}, null, null);
        addMethod("GeoPoints", "getLon", null, false, doubleType, new Type[] {}, null, null);
        addMethod("GeoPoints", "getLats", null, false, getType(doubleType.struct, 1), new Type[] {}, null, null);
        addMethod("GeoPoints", "getLons", null, false, getType(doubleType.struct, 1), new Type[] {}, null, null);
        // geo distance functions... so many...
        addMethod("GeoPoints", "factorDistance", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "factorDistanceWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "factorDistance02", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "factorDistance13", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "arcDistance", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "arcDistanceWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "arcDistanceInKm", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "arcDistanceInKmWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "arcDistanceInMiles", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "arcDistanceInMilesWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "distance", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "distanceWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "distanceInKm", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "distanceInKmWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "distanceInMiles", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "distanceInMilesWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "geohashDistance", null, false, doubleType,
                  new Type[] { stringType }, null, null);
        addMethod("GeoPoints", "geohashDistanceInKm", null, false, doubleType,
                  new Type[] { stringType }, null, null);
        addMethod("GeoPoints", "geohashDistanceInMiles", null, false, doubleType,
                  new Type[] { stringType }, null, null);
    }

    private void copyStructs() {
        copyStruct("Void", "Object");
        copyStruct("Boolean", "Object");
        copyStruct("Byte", "Number", "Object");
        copyStruct("Short", "Number", "Object");
        copyStruct("Character", "Object");
        copyStruct("Integer", "Number", "Object");
        copyStruct("Long", "Number", "Object");
        copyStruct("Float", "Number", "Object");
        copyStruct("Double", "Number", "Object");

        copyStruct("Number", "Object");
        copyStruct("CharSequence", "Object");
        copyStruct("String", "CharSequence", "Object");

        copyStruct("List", "Collection", "Object");
        copyStruct("ArrayList", "List", "Collection", "Object");
        copyStruct("List<Object>", "Collection<Object>", "Object");
        copyStruct("ArrayList<Object>", "List<Object>", "Collection<Object>", "Object");
        copyStruct("List<String>", "Collection<String>", "Object");
        copyStruct("ArrayList<String>", "List<String>", "Collection<String>", "Object");

        copyStruct("Set", "Collection", "Object");
        copyStruct("HashSet", "Set", "Collection", "Object");
        copyStruct("Set<Object>", "Collection<Object>", "Object");
        copyStruct("HashSet<Object>", "Set<Object>", "Collection<Object>", "Object");
        copyStruct("Set<String>", "Collection<String>", "Object");
        copyStruct("HashSet<String>", "Set<String>", "Collection<String>", "Object");

        copyStruct("Map", "Object");
        copyStruct("HashMap", "Map", "Object");
        copyStruct("Map<Object,Object>", "Object");
        copyStruct("HashMap<Object,Object>", "Map<Object,Object>", "Object");
        copyStruct("Map<String,def>", "Object");
        copyStruct("HashMap<String,def>", "Map<String,def>", "Object");
        copyStruct("Map<String,Object>", "Object");
        copyStruct("HashMap<String,Object>", "Map<String,Object>", "Object");

        copyStruct("Executable", "Object");

        copyStruct("Exception", "Object");
        copyStruct("ArithmeticException", "Exception", "Object");
        copyStruct("IllegalArgumentException", "Exception", "Object");
        copyStruct("IllegalStateException", "Exception", "Object");
        copyStruct("NumberFormatException", "Exception", "Object");

        copyStruct("GeoPoint", "Object");
        copyStruct("Strings", "List<String>", "Collection<String>", "Object");
        copyStruct("Longs", "List", "Collection", "Object");
        copyStruct("Doubles", "List", "Collection", "Object");
        copyStruct("GeoPoints", "List", "Collection", "Object");
    }

    private void addTransforms() {
        addTransform(booleanType, byteType, "Utility", "booleanTobyte", true);
        addTransform(booleanType, shortType, "Utility", "booleanToshort", true);
        addTransform(booleanType, charType, "Utility", "booleanTochar", true);
        addTransform(booleanType, intType, "Utility", "booleanToint", true);
        addTransform(booleanType, longType, "Utility", "booleanTolong", true);
        addTransform(booleanType, floatType, "Utility", "booleanTofloat", true);
        addTransform(booleanType, doubleType, "Utility", "booleanTodouble", true);
        addTransform(booleanType, objectType, "Boolean", "valueOf", true);
        addTransform(booleanType, defType, "Boolean", "valueOf", true);
        addTransform(booleanType, numberType, "Utility", "booleanToInteger", true);
        addTransform(booleanType, booleanobjType, "Boolean", "valueOf", true);

        addTransform(byteType, booleanType, "Utility", "byteToboolean", true);
        addTransform(byteType, objectType, "Byte", "valueOf", true);
        addTransform(byteType, defType, "Byte", "valueOf", true);
        addTransform(byteType, numberType, "Byte", "valueOf", true);
        addTransform(byteType, byteobjType, "Byte", "valueOf", true);
        addTransform(byteType, shortobjType, "Utility", "byteToShort", true);
        addTransform(byteType, charobjType, "Utility", "byteToCharacter", true);
        addTransform(byteType, intobjType, "Utility", "byteToInteger", true);
        addTransform(byteType, longobjType, "Utility", "byteToLong", true);
        addTransform(byteType, floatobjType, "Utility", "byteToFloat", true);
        addTransform(byteType, doubleobjType, "Utility", "byteToDouble", true);

        addTransform(shortType, booleanType, "Utility", "shortToboolean", true);
        addTransform(shortType, objectType, "Short", "valueOf", true);
        addTransform(shortType, defType, "Short", "valueOf", true);
        addTransform(shortType, numberType, "Short", "valueOf", true);
        addTransform(shortType, byteobjType, "Utility", "shortToByte", true);
        addTransform(shortType, shortobjType, "Short", "valueOf", true);
        addTransform(shortType, charobjType, "Utility", "shortToCharacter", true);
        addTransform(shortType, intobjType, "Utility", "shortToInteger", true);
        addTransform(shortType, longobjType, "Utility", "shortToLong", true);
        addTransform(shortType, floatobjType, "Utility", "shortToFloat", true);
        addTransform(shortType, doubleobjType, "Utility", "shortToDouble", true);

        addTransform(charType, booleanType, "Utility", "charToboolean", true);
        addTransform(charType, objectType, "Character", "valueOf", true);
        addTransform(charType, defType, "Character", "valueOf", true);
        addTransform(charType, numberType, "Utility", "charToInteger", true);
        addTransform(charType, byteobjType, "Utility", "charToByte", true);
        addTransform(charType, shortobjType, "Utility", "charToShort", true);
        addTransform(charType, charobjType, "Character", "valueOf", true);
        addTransform(charType, intobjType, "Utility", "charToInteger", true);
        addTransform(charType, longobjType, "Utility", "charToLong", true);
        addTransform(charType, floatobjType, "Utility", "charToFloat", true);
        addTransform(charType, doubleobjType, "Utility", "charToDouble", true);
        addTransform(charType, stringType, "Utility", "charToString", true);

        addTransform(intType, booleanType, "Utility", "intToboolean", true);
        addTransform(intType, objectType, "Integer", "valueOf", true);
        addTransform(intType, defType, "Integer", "valueOf", true);
        addTransform(intType, numberType, "Integer", "valueOf", true);
        addTransform(intType, byteobjType, "Utility", "intToByte", true);
        addTransform(intType, shortobjType, "Utility", "intToShort", true);
        addTransform(intType, charobjType, "Utility", "intToCharacter", true);
        addTransform(intType, intobjType, "Integer", "valueOf", true);
        addTransform(intType, longobjType, "Utility", "intToLong", true);
        addTransform(intType, floatobjType, "Utility", "intToFloat", true);
        addTransform(intType, doubleobjType, "Utility", "intToDouble", true);

        addTransform(longType, booleanType, "Utility", "longToboolean", true);
        addTransform(longType, objectType, "Long", "valueOf", true);
        addTransform(longType, defType, "Long", "valueOf", true);
        addTransform(longType, numberType, "Long", "valueOf", true);
        addTransform(longType, byteobjType, "Utility", "longToByte", true);
        addTransform(longType, shortobjType, "Utility", "longToShort", true);
        addTransform(longType, charobjType, "Utility", "longToCharacter", true);
        addTransform(longType, intobjType, "Utility", "longToInteger", true);
        addTransform(longType, longobjType, "Long", "valueOf", true);
        addTransform(longType, floatobjType, "Utility", "longToFloat", true);
        addTransform(longType, doubleobjType, "Utility", "longToDouble", true);

        addTransform(floatType, booleanType, "Utility", "floatToboolean", true);
        addTransform(floatType, objectType, "Float", "valueOf", true);
        addTransform(floatType, defType, "Float", "valueOf", true);
        addTransform(floatType, numberType, "Float", "valueOf", true);
        addTransform(floatType, byteobjType, "Utility", "floatToByte", true);
        addTransform(floatType, shortobjType, "Utility", "floatToShort", true);
        addTransform(floatType, charobjType, "Utility", "floatToCharacter", true);
        addTransform(floatType, intobjType, "Utility", "floatToInteger", true);
        addTransform(floatType, longobjType, "Utility", "floatToLong", true);
        addTransform(floatType, floatobjType, "Float", "valueOf", true);
        addTransform(floatType, doubleobjType, "Utility", "floatToDouble", true);

        addTransform(doubleType, booleanType, "Utility", "doubleToboolean", true);
        addTransform(doubleType, objectType, "Double", "valueOf", true);
        addTransform(doubleType, defType, "Double", "valueOf", true);
        addTransform(doubleType, numberType, "Double", "valueOf", true);
        addTransform(doubleType, byteobjType, "Utility", "doubleToByte", true);
        addTransform(doubleType, shortobjType, "Utility", "doubleToShort", true);
        addTransform(doubleType, charobjType, "Utility", "doubleToCharacter", true);
        addTransform(doubleType, intobjType, "Utility", "doubleToInteger", true);
        addTransform(doubleType, longobjType, "Utility", "doubleToLong", true);
        addTransform(doubleType, floatobjType, "Utility", "doubleToFloat", true);
        addTransform(doubleType, doubleobjType, "Double", "valueOf", true);

        addTransform(objectType, booleanType, "Boolean", "booleanValue", false);
        addTransform(objectType, byteType, "Number", "byteValue", false);
        addTransform(objectType, shortType, "Number", "shortValue", false);
        addTransform(objectType, charType, "Character", "charValue", false);
        addTransform(objectType, intType, "Number", "intValue", false);
        addTransform(objectType, longType, "Number", "longValue", false);
        addTransform(objectType, floatType, "Number", "floatValue", false);
        addTransform(objectType, doubleType, "Number", "doubleValue", false);

        addTransform(defType, booleanType, "Def", "DefToboolean", true);
        addTransform(defType, byteType, "Def", "DefTobyte", true);
        addTransform(defType, shortType, "Def", "DefToshort", true);
        addTransform(defType, charType, "Def", "DefTochar", true);
        addTransform(defType, intType, "Def", "DefToint", true);
        addTransform(defType, longType, "Def", "DefTolong", true);
        addTransform(defType, floatType, "Def", "DefTofloat", true);
        addTransform(defType, doubleType, "Def", "DefTodouble", true);
        addTransform(defType, booleanobjType, "Def", "DefToBoolean", true);
        addTransform(defType, byteobjType, "Def", "DefToByte", true);
        addTransform(defType, shortobjType, "Def", "DefToShort", true);
        addTransform(defType, charobjType, "Def", "DefToCharacter", true);
        addTransform(defType, intobjType, "Def", "DefToInteger", true);
        addTransform(defType, longobjType, "Def", "DefToLong", true);
        addTransform(defType, floatobjType, "Def", "DefToFloat", true);
        addTransform(defType, doubleobjType, "Def", "DefToDouble", true);

        addTransform(numberType, booleanType, "Utility", "NumberToboolean", true);
        addTransform(numberType, byteType, "Number", "byteValue", false);
        addTransform(numberType, shortType, "Number", "shortValue", false);
        addTransform(numberType, charType, "Utility", "NumberTochar", true);
        addTransform(numberType, intType, "Number", "intValue", false);
        addTransform(numberType, longType, "Number", "longValue", false);
        addTransform(numberType, floatType, "Number", "floatValue", false);
        addTransform(numberType, doubleType, "Number", "doubleValue", false);
        addTransform(numberType, booleanobjType, "Utility", "NumberToBoolean", true);
        addTransform(numberType, byteobjType, "Utility", "NumberToByte", true);
        addTransform(numberType, shortobjType, "Utility", "NumberToShort", true);
        addTransform(numberType, charobjType, "Utility", "NumberToCharacter", true);
        addTransform(numberType, intobjType, "Utility", "NumberToInteger", true);
        addTransform(numberType, longobjType, "Utility", "NumberToLong", true);
        addTransform(numberType, floatobjType, "Utility", "NumberToFloat", true);
        addTransform(numberType, doubleobjType, "Utility", "NumberToDouble", true);

        addTransform(booleanobjType, booleanType, "Boolean", "booleanValue", false);
        addTransform(booleanobjType, byteType, "Utility", "BooleanTobyte", true);
        addTransform(booleanobjType, shortType, "Utility", "BooleanToshort", true);
        addTransform(booleanobjType, charType, "Utility", "BooleanTochar", true);
        addTransform(booleanobjType, intType, "Utility", "BooleanToint", true);
        addTransform(booleanobjType, longType, "Utility", "BooleanTolong", true);
        addTransform(booleanobjType, floatType, "Utility", "BooleanTofloat", true);
        addTransform(booleanobjType, doubleType, "Utility", "BooleanTodouble", true);
        addTransform(booleanobjType, numberType, "Utility", "BooleanToLong", true);
        addTransform(booleanobjType, byteobjType, "Utility", "BooleanToByte", true);
        addTransform(booleanobjType, shortobjType, "Utility", "BooleanToShort", true);
        addTransform(booleanobjType, charobjType, "Utility", "BooleanToCharacter", true);
        addTransform(booleanobjType, intobjType, "Utility", "BooleanToInteger", true);
        addTransform(booleanobjType, longobjType, "Utility", "BooleanToLong", true);
        addTransform(booleanobjType, floatobjType, "Utility", "BooleanToFloat", true);
        addTransform(booleanobjType, doubleobjType, "Utility", "BooleanToDouble", true);

        addTransform(byteobjType, booleanType, "Utility", "ByteToboolean", true);
        addTransform(byteobjType, byteType, "Byte", "byteValue", false);
        addTransform(byteobjType, shortType, "Byte", "shortValue", false);
        addTransform(byteobjType, charType, "Utility", "ByteTochar", true);
        addTransform(byteobjType, intType, "Byte", "intValue", false);
        addTransform(byteobjType, longType, "Byte", "longValue", false);
        addTransform(byteobjType, floatType, "Byte", "floatValue", false);
        addTransform(byteobjType, doubleType, "Byte", "doubleValue", false);
        addTransform(byteobjType, booleanobjType, "Utility", "NumberToBoolean", true);
        addTransform(byteobjType, shortobjType, "Utility", "NumberToShort", true);
        addTransform(byteobjType, charobjType, "Utility", "NumberToCharacter", true);
        addTransform(byteobjType, intobjType, "Utility", "NumberToInteger", true);
        addTransform(byteobjType, longobjType, "Utility", "NumberToLong", true);
        addTransform(byteobjType, floatobjType, "Utility", "NumberToFloat", true);
        addTransform(byteobjType, doubleobjType, "Utility", "NumberToDouble", true);

        addTransform(shortobjType, booleanType, "Utility", "ShortToboolean", true);
        addTransform(shortobjType, byteType, "Short", "byteValue", false);
        addTransform(shortobjType, shortType, "Short", "shortValue", false);
        addTransform(shortobjType, charType, "Utility", "ShortTochar", true);
        addTransform(shortobjType, intType, "Short", "intValue", false);
        addTransform(shortobjType, longType, "Short", "longValue", false);
        addTransform(shortobjType, floatType, "Short", "floatValue", false);
        addTransform(shortobjType, doubleType, "Short", "doubleValue", false);
        addTransform(shortobjType, booleanobjType, "Utility", "NumberToBoolean", true);
        addTransform(shortobjType, byteobjType, "Utility", "NumberToByte", true);
        addTransform(shortobjType, charobjType, "Utility", "NumberToCharacter", true);
        addTransform(shortobjType, intobjType, "Utility", "NumberToInteger", true);
        addTransform(shortobjType, longobjType, "Utility", "NumberToLong", true);
        addTransform(shortobjType, floatobjType, "Utility", "NumberToFloat", true);
        addTransform(shortobjType, doubleobjType, "Utility", "NumberToDouble", true);

        addTransform(charobjType, booleanType, "Utility", "CharacterToboolean", true);
        addTransform(charobjType, byteType, "Utility", "CharacterTobyte", true);
        addTransform(charobjType, shortType, "Utility", "CharacterToshort", true);
        addTransform(charobjType, charType, "Character", "charValue", false);
        addTransform(charobjType, intType, "Utility", "CharacterToint", true);
        addTransform(charobjType, longType, "Utility", "CharacterTolong", true);
        addTransform(charobjType, floatType, "Utility", "CharacterTofloat", true);
        addTransform(charobjType, doubleType, "Utility", "CharacterTodouble", true);
        addTransform(charobjType, booleanobjType, "Utility", "CharacterToBoolean", true);
        addTransform(charobjType, byteobjType, "Utility", "CharacterToByte", true);
        addTransform(charobjType, shortobjType, "Utility", "CharacterToShort", true);
        addTransform(charobjType, intobjType, "Utility", "CharacterToInteger", true);
        addTransform(charobjType, longobjType, "Utility", "CharacterToLong", true);
        addTransform(charobjType, floatobjType, "Utility", "CharacterToFloat", true);
        addTransform(charobjType, doubleobjType, "Utility", "CharacterToDouble", true);
        addTransform(charobjType, stringType, "Utility", "CharacterToString", true);

        addTransform(intobjType, booleanType, "Utility", "IntegerToboolean", true);
        addTransform(intobjType, byteType, "Integer", "byteValue", false);
        addTransform(intobjType, shortType, "Integer", "shortValue", false);
        addTransform(intobjType, charType, "Utility", "IntegerTochar", true);
        addTransform(intobjType, intType, "Integer", "intValue", false);
        addTransform(intobjType, longType, "Integer", "longValue", false);
        addTransform(intobjType, floatType, "Integer", "floatValue", false);
        addTransform(intobjType, doubleType, "Integer", "doubleValue", false);
        addTransform(intobjType, booleanobjType, "Utility", "NumberToBoolean", true);
        addTransform(intobjType, byteobjType, "Utility", "NumberToByte", true);
        addTransform(intobjType, shortobjType, "Utility", "NumberToShort", true);
        addTransform(intobjType, charobjType, "Utility", "NumberToCharacter", true);
        addTransform(intobjType, longobjType, "Utility", "NumberToLong", true);
        addTransform(intobjType, floatobjType, "Utility", "NumberToFloat", true);
        addTransform(intobjType, doubleobjType, "Utility", "NumberToDouble", true);

        addTransform(longobjType, booleanType, "Utility", "LongToboolean", true);
        addTransform(longobjType, byteType, "Long", "byteValue", false);
        addTransform(longobjType, shortType, "Long", "shortValue", false);
        addTransform(longobjType, charType, "Utility", "LongTochar", true);
        addTransform(longobjType, intType, "Long", "intValue", false);
        addTransform(longobjType, longType, "Long", "longValue", false);
        addTransform(longobjType, floatType, "Long", "floatValue", false);
        addTransform(longobjType, doubleType, "Long", "doubleValue", false);
        addTransform(longobjType, booleanobjType, "Utility", "NumberToBoolean", true);
        addTransform(longobjType, byteobjType, "Utility", "NumberToByte", true);
        addTransform(longobjType, shortobjType, "Utility", "NumberToShort", true);
        addTransform(longobjType, charobjType, "Utility", "NumberToCharacter", true);
        addTransform(longobjType, intobjType, "Utility", "NumberToInteger", true);
        addTransform(longobjType, floatobjType, "Utility", "NumberToFloat", true);
        addTransform(longobjType, doubleobjType, "Utility", "NumberToDouble", true);

        addTransform(floatobjType, booleanType, "Utility", "FloatToboolean", true);
        addTransform(floatobjType, byteType, "Float", "byteValue", false);
        addTransform(floatobjType, shortType, "Float", "shortValue", false);
        addTransform(floatobjType, charType, "Utility", "FloatTochar", true);
        addTransform(floatobjType, intType, "Float", "intValue", false);
        addTransform(floatobjType, longType, "Float", "longValue", false);
        addTransform(floatobjType, floatType, "Float", "floatValue", false);
        addTransform(floatobjType, doubleType, "Float", "doubleValue", false);
        addTransform(floatobjType, booleanobjType, "Utility", "NumberToBoolean", true);
        addTransform(floatobjType, byteobjType, "Utility", "NumberToByte", true);
        addTransform(floatobjType, shortobjType, "Utility", "NumberToShort", true);
        addTransform(floatobjType, charobjType, "Utility", "NumberToCharacter", true);
        addTransform(floatobjType, intobjType, "Utility", "NumberToInteger", true);
        addTransform(floatobjType, longobjType, "Utility", "NumberToLong", true);
        addTransform(floatobjType, doubleobjType, "Utility", "NumberToDouble", true);

        addTransform(doubleobjType, booleanType, "Utility", "DoubleToboolean", true);
        addTransform(doubleobjType, byteType, "Double", "byteValue", false);
        addTransform(doubleobjType, shortType, "Double", "shortValue", false);
        addTransform(doubleobjType, charType, "Utility", "DoubleTochar", true);
        addTransform(doubleobjType, intType, "Double", "intValue", false);
        addTransform(doubleobjType, longType, "Double", "longValue", false);
        addTransform(doubleobjType, floatType, "Double", "floatValue", false);
        addTransform(doubleobjType, doubleType, "Double", "doubleValue", false);
        addTransform(doubleobjType, booleanobjType, "Utility", "NumberToBoolean", true);
        addTransform(doubleobjType, byteobjType, "Utility", "NumberToByte", true);
        addTransform(doubleobjType, shortobjType, "Utility", "NumberToShort", true);
        addTransform(doubleobjType, charobjType, "Utility", "NumberToCharacter", true);
        addTransform(doubleobjType, intobjType, "Utility", "NumberToInteger", true);
        addTransform(doubleobjType, longobjType, "Utility", "NumberToLong", true);
        addTransform(doubleobjType, floatobjType, "Utility", "NumberToFloat", true);

        addTransform(stringType, charType, "Utility", "StringTochar", true);
        addTransform(stringType, charobjType, "Utility", "StringToCharacter", true);
    }

    private void addRuntimeClasses() {
        addRuntimeClass(booleanType.struct);
        addRuntimeClass(byteType.struct);
        addRuntimeClass(shortType.struct);
        addRuntimeClass(charType.struct);
        addRuntimeClass(intType.struct);
        addRuntimeClass(longType.struct);
        addRuntimeClass(floatType.struct);
        addRuntimeClass(doubleType.struct);

        addRuntimeClass(booleanobjType.struct);
        addRuntimeClass(byteobjType.struct);
        addRuntimeClass(shortobjType.struct);
        addRuntimeClass(charobjType.struct);
        addRuntimeClass(intobjType.struct);
        addRuntimeClass(longobjType.struct);
        addRuntimeClass(floatobjType.struct);
        addRuntimeClass(doubleobjType.struct);

        addRuntimeClass(objectType.struct);
        addRuntimeClass(numberType.struct);
        addRuntimeClass(charseqType.struct);
        addRuntimeClass(stringType.struct);

        addRuntimeClass(oitrType.struct);
        addRuntimeClass(ocollectionType.struct);
        addRuntimeClass(olistType.struct);
        addRuntimeClass(oarraylistType.struct);
        addRuntimeClass(osetType.struct);
        addRuntimeClass(ohashsetType.struct);
        addRuntimeClass(oomapType.struct);
        addRuntimeClass(oohashmapType.struct);

        addRuntimeClass(exceptionType.struct);

        addRuntimeClass(geoPointType.struct);
        addRuntimeClass(stringsType.struct);
        addRuntimeClass(longsType.struct);
        addRuntimeClass(doublesType.struct);
        addRuntimeClass(geoPointsType.struct);
    }

    private final void addStruct(final String name, final Class<?> clazz) {
        if (!name.matches("^[_a-zA-Z][<>,_a-zA-Z0-9]*$")) {
            throw new IllegalArgumentException("Invalid struct name [" + name + "].");
        }

        if (structsMap.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate struct name [" + name + "].");
        }

        final Struct struct = new Struct(name, clazz, org.objectweb.asm.Type.getType(clazz));

        structsMap.put(name, struct);
    }

    private final void addConstructor(final String struct, final String name, final Type[] args, final Type[] genargs) {
        final Struct owner = structsMap.get(struct);

        if (owner == null) {
            throw new IllegalArgumentException(
                "Owner struct [" + struct + "] not defined for constructor [" + name + "].");
        }

        if (!name.matches("^[_a-zA-Z][_a-zA-Z0-9]*$")) {
            throw new IllegalArgumentException(
                "Invalid constructor name [" + name + "] with the struct [" + owner.name + "].");
        }

        if (owner.constructors.containsKey(name)) {
            throw new IllegalArgumentException(
                "Duplicate constructor name [" + name + "] found within the struct [" + owner.name + "].");
        }

        if (owner.statics.containsKey(name)) {
            throw new IllegalArgumentException("Constructors and functions may not have the same name" +
                " [" + name + "] within the same struct [" + owner.name + "].");
        }

        if (owner.methods.containsKey(name)) {
            throw new IllegalArgumentException("Constructors and methods may not have the same name" +
                " [" + name + "] within the same struct [" + owner.name + "].");
        }

        final Class<?>[] classes = new Class<?>[args.length];

        for (int count = 0; count < classes.length; ++count) {
            if (genargs != null) {
                try {
                    genargs[count].clazz.asSubclass(args[count].clazz);
                } catch (final ClassCastException exception) {
                    throw new ClassCastException("Generic argument [" + genargs[count].name + "]" +
                        " is not a sub class of [" + args[count].name + "] in the constructor" +
                        " [" + name + " ] from the struct [" + owner.name + "].");
                }
            }

            classes[count] = args[count].clazz;
        }

        final java.lang.reflect.Constructor<?> reflect;

        try {
            reflect = owner.clazz.getConstructor(classes);
        } catch (final NoSuchMethodException exception) {
            throw new IllegalArgumentException("Constructor [" + name + "] not found for class" +
                " [" + owner.clazz.getName() + "] with arguments " + Arrays.toString(classes) + ".");
        }

        final org.objectweb.asm.commons.Method asm = org.objectweb.asm.commons.Method.getMethod(reflect);
        final Constructor constructor =
            new Constructor(name, owner, Arrays.asList(genargs != null ? genargs : args), asm, reflect);

        owner.constructors.put(name, constructor);
    }

    private final void addMethod(final String struct, final String name, final String alias, final boolean statik,
                                final Type rtn, final Type[] args, final Type genrtn, final Type[] genargs) {
        final Struct owner = structsMap.get(struct);

        if (owner == null) {
            throw new IllegalArgumentException("Owner struct [" + struct + "] not defined" +
                " for " + (statik ? "function" : "method") + " [" + name + "].");
        }

        if (!name.matches("^[_a-zA-Z][_a-zA-Z0-9]*$")) {
            throw new IllegalArgumentException("Invalid " + (statik ? "function" : "method") +
                " name [" + name + "] with the struct [" + owner.name + "].");
        }

        if (owner.constructors.containsKey(name)) {
            throw new IllegalArgumentException("Constructors and " + (statik ? "functions" : "methods") +
                " may not have the same name [" + name + "] within the same struct" +
                " [" + owner.name + "].");
        }

        if (owner.statics.containsKey(name)) {
            if (statik) {
                throw new IllegalArgumentException(
                    "Duplicate function name [" + name + "] found within the struct [" + owner.name + "].");
            } else {
                throw new IllegalArgumentException("Functions and methods may not have the same name" +
                    " [" + name + "] within the same struct [" + owner.name + "].");
            }
        }

        if (owner.methods.containsKey(name)) {
            if (statik) {
                throw new IllegalArgumentException("Functions and methods may not have the same name" +
                    " [" + name + "] within the same struct [" + owner.name + "].");
            } else {
                throw new IllegalArgumentException("Duplicate method name [" + name + "]" +
                    " found within the struct [" + owner.name + "].");
            }
        }

        if (genrtn != null) {
            try {
                genrtn.clazz.asSubclass(rtn.clazz);
            } catch (final ClassCastException exception) {
                throw new ClassCastException("Generic return [" + genrtn.clazz.getCanonicalName() + "]" +
                    " is not a sub class of [" + rtn.clazz.getCanonicalName() + "] in the method" +
                    " [" + name + " ] from the struct [" + owner.name + "].");
            }
        }

        if (genargs != null && genargs.length != args.length) {
            throw new IllegalArgumentException("Generic arguments arity [" +  genargs.length + "] is not the same as " +
                (statik ? "function" : "method") + " [" + name + "] arguments arity" +
                " [" + args.length + "] within the struct [" + owner.name + "].");
        }

        final Class<?>[] classes = new Class<?>[args.length];

        for (int count = 0; count < classes.length; ++count) {
            if (genargs != null) {
                try {
                    genargs[count].clazz.asSubclass(args[count].clazz);
                } catch (final ClassCastException exception) {
                    throw new ClassCastException("Generic argument [" + genargs[count].name + "] is not a sub class" +
                        " of [" + args[count].name + "] in the " + (statik ? "function" : "method") +
                        " [" + name + " ] from the struct [" + owner.name + "].");
                }
            }

            classes[count] = args[count].clazz;
        }

        final java.lang.reflect.Method reflect;

        try {
            reflect = owner.clazz.getMethod(alias == null ? name : alias, classes);
        } catch (final NoSuchMethodException exception) {
            throw new IllegalArgumentException((statik ? "Function" : "Method") +
                " [" + (alias == null ? name : alias) + "] not found for class [" + owner.clazz.getName() + "]" +
                " with arguments " + Arrays.toString(classes) + ".");
        }

        if (!reflect.getReturnType().equals(rtn.clazz)) {
            throw new IllegalArgumentException("Specified return type class [" + rtn.clazz + "]" +
                " does not match the found return type class [" + reflect.getReturnType() + "] for the " +
                (statik ? "function" : "method") + " [" + name + "]" +
                " within the struct [" + owner.name + "].");
        }

        final org.objectweb.asm.commons.Method asm = org.objectweb.asm.commons.Method.getMethod(reflect);

        MethodHandle handle;

        try {
            handle = MethodHandles.publicLookup().in(owner.clazz).unreflect(reflect);
        } catch (final IllegalAccessException exception) {
            throw new IllegalArgumentException("Method [" + (alias == null ? name : alias) + "]" +
                " not found for class [" + owner.clazz.getName() + "]" +
                " with arguments " + Arrays.toString(classes) + ".");
        }

        final Method method = new Method(name, owner, genrtn != null ? genrtn : rtn,
            Arrays.asList(genargs != null ? genargs : args), asm, reflect, handle);
        final int modifiers = reflect.getModifiers();

        if (statik) {
            if (!java.lang.reflect.Modifier.isStatic(modifiers)) {
                throw new IllegalArgumentException("Function [" + name + "]" +
                    " within the struct [" + owner.name + "] is not linked to a static Java method.");
            }

            owner.functions.put(name, method);
        } else {
            if (java.lang.reflect.Modifier.isStatic(modifiers)) {
                throw new IllegalArgumentException("Method [" + name + "]" +
                    " within the struct [" + owner.name + "] is not linked to a non-static Java method.");
            }

            owner.methods.put(name, method);
        }
    }

    private final void addField(final String struct, final String name, final String alias,
                               final boolean statik, final Type type, final Type generic) {
        final Struct owner = structsMap.get(struct);

        if (owner == null) {
            throw new IllegalArgumentException("Owner struct [" + struct + "] not defined for " +
                (statik ? "static" : "member") + " [" + name + "].");
        }

        if (!name.matches("^[_a-zA-Z][_a-zA-Z0-9]*$")) {
            throw new IllegalArgumentException("Invalid " + (statik ? "static" : "member") +
                " name [" + name + "] with the struct [" + owner.name + "].");
        }

        if (owner.statics.containsKey(name)) {
            if (statik) {
                throw new IllegalArgumentException("Duplicate static name [" + name + "]" +
                    " found within the struct [" + owner.name + "].");
            } else {
                throw new IllegalArgumentException("Statics and members may not have the same name " +
                    "[" + name + "] within the same struct [" + owner.name + "].");
            }
        }

        if (owner.members.containsKey(name)) {
            if (statik) {
                throw new IllegalArgumentException("Statics and members may not have the same name " +
                    "[" + name + "] within the same struct [" + owner.name + "].");
            } else {
                throw new IllegalArgumentException("Duplicate member name [" + name + "]" +
                    " found within the struct [" + owner.name + "].");
            }
        }

        if (generic != null) {
            try {
                generic.clazz.asSubclass(type.clazz);
            } catch (final ClassCastException exception) {
                throw new ClassCastException("Generic type [" + generic.clazz.getCanonicalName() + "]" +
                    " is not a sub class of [" + type.clazz.getCanonicalName() + "] for the field" +
                    " [" + name + " ] from the struct [" + owner.name + "].");
            }
        }

        java.lang.reflect.Field reflect;

        try {
            reflect = owner.clazz.getField(alias == null ? name : alias);
        } catch (final NoSuchFieldException exception) {
            throw new IllegalArgumentException("Field [" + (alias == null ? name : alias) + "]" +
                " not found for class [" + owner.clazz.getName() + "].");
        }

        MethodHandle getter = null;
        MethodHandle setter = null;

        try {
            if (!statik) {
                getter = MethodHandles.publicLookup().unreflectGetter(reflect);
                setter = MethodHandles.publicLookup().unreflectSetter(reflect);
            }
        } catch (final IllegalAccessException exception) {
            throw new IllegalArgumentException("Getter/Setter [" + (alias == null ? name : alias) + "]" +
                " not found for class [" + owner.clazz.getName() + "].");
        }

        final Field field = new Field(name, owner, generic == null ? type : generic, type, reflect, getter, setter);
        final int modifiers = reflect.getModifiers();

        if (statik) {
            if (!java.lang.reflect.Modifier.isStatic(modifiers)) {
                throw new IllegalArgumentException();
            }

            if (!java.lang.reflect.Modifier.isFinal(modifiers)) {
                throw new IllegalArgumentException("Static [" + name + "]" +
                    " within the struct [" + owner.name + "] is not linked to static Java field.");
            }

            owner.statics.put(alias == null ? name : alias, field);
        } else {
            if (java.lang.reflect.Modifier.isStatic(modifiers)) {
                throw new IllegalArgumentException("Member [" + name + "]" +
                    " within the struct [" + owner.name + "] is not linked to non-static Java field.");
            }

            owner.members.put(alias == null ? name : alias, field);
        }
    }

    private final void copyStruct(final String struct, final String... children) {
        final Struct owner = structsMap.get(struct);

        if (owner == null) {
            throw new IllegalArgumentException("Owner struct [" + struct + "] not defined for copy.");
        }

        for (int count = 0; count < children.length; ++count) {
            final Struct child = structsMap.get(children[count]);

            if (struct == null) {
                throw new IllegalArgumentException("Child struct [" + children[count] + "]" +
                    " not defined for copy to owner struct [" + owner.name + "].");
            }

            try {
                owner.clazz.asSubclass(child.clazz);
            } catch (final ClassCastException exception) {
                throw new ClassCastException("Child struct [" + child.name + "]" +
                    " is not a super type of owner struct [" + owner.name + "] in copy.");
            }

            final boolean object = child.clazz.equals(Object.class) &&
                java.lang.reflect.Modifier.isInterface(owner.clazz.getModifiers());

            for (final Method method : child.methods.values()) {
                if (owner.methods.get(method.name) == null) {
                    final Class<?> clazz = object ? Object.class : owner.clazz;

                    java.lang.reflect.Method reflect;
                    MethodHandle handle;

                    try {
                        reflect = clazz.getMethod(method.method.getName(), method.reflect.getParameterTypes());
                    } catch (final NoSuchMethodException exception) {
                        throw new IllegalArgumentException("Method [" + method.method.getName() + "] not found for" +
                            " class [" + owner.clazz.getName() + "] with arguments " +
                            Arrays.toString(method.reflect.getParameterTypes()) + ".");
                    }

                    try {
                        handle = MethodHandles.publicLookup().in(owner.clazz).unreflect(reflect);
                    } catch (final IllegalAccessException exception) {
                        throw new IllegalArgumentException("Method [" + method.method.getName() + "] not found for" +
                            " class [" + owner.clazz.getName() + "] with arguments " +
                            Arrays.toString(method.reflect.getParameterTypes()) + ".");
                    }

                    owner.methods.put(method.name,
                        new Method(method.name, owner, method.rtn, method.arguments, method.method, reflect, handle));
                }
            }

            for (final Field field : child.members.values()) {
                if (owner.members.get(field.name) == null) {
                    java.lang.reflect.Field reflect;
                    MethodHandle getter;
                    MethodHandle setter;

                    try {
                        reflect = owner.clazz.getField(field.reflect.getName());
                    } catch (final NoSuchFieldException exception) {
                        throw new IllegalArgumentException("Field [" + field.reflect.getName() + "]" +
                            " not found for class [" + owner.clazz.getName() + "].");
                    }

                    try {
                        getter = MethodHandles.publicLookup().unreflectGetter(reflect);
                        setter = MethodHandles.publicLookup().unreflectSetter(reflect);
                    } catch (final IllegalAccessException exception) {
                        throw new IllegalArgumentException("Getter/Setter [" + field.name + "]" +
                            " not found for class [" + owner.clazz.getName() + "].");
                    }

                    owner.members.put(field.name,
                        new Field(field.name, owner, field.type, field.generic, reflect, getter, setter));
                }
            }
        }
    }

    private final void addTransform(final Type from, final Type to, final String struct,
                                   final String name, final boolean statik) {
        final Struct owner = structsMap.get(struct);

        if (owner == null) {
            throw new IllegalArgumentException("Owner struct [" + struct + "] not defined for" +
                " transform with cast type from [" + from.name + "] and cast type to [" + to.name + "].");
        }

        if (from.equals(to)) {
            throw new IllegalArgumentException("Transform with owner struct [" + owner.name + "] cannot" +
                " have cast type from [" + from.name + "] be the same as cast type to [" + to.name + "].");
        }

        final Cast cast = new Cast(from, to);

        if (transformsMap.containsKey(cast)) {
            throw new IllegalArgumentException("Transform with owner struct [" + owner.name + "]" +
                " and cast type from [" + from.name + "] to cast type to [" + to.name + "] already defined.");
        }

        Method method;
        Type upcast = null;
        Type downcast = null;

        if (statik) {
            method = owner.functions.get(name);

            if (method == null) {
                throw new IllegalArgumentException("Transform with owner struct [" + owner.name + "]" +
                    " and cast type from [" + from.name + "] to cast type to [" + to.name +
                    "] using a function [" + name + "] that is not defined.");
            }

            if (method.arguments.size() != 1) {
                throw new IllegalArgumentException("Transform with owner struct [" + owner.name + "]" +
                    " and cast type from [" + from.name + "] to cast type to [" + to.name +
                    "] using function [" + name + "] does not have a single type argument.");
            }

            Type argument = method.arguments.get(0);

            try {
                from.clazz.asSubclass(argument.clazz);
            } catch (final ClassCastException cce0) {
                try {
                    argument.clazz.asSubclass(from.clazz);
                    upcast = argument;
                } catch (final ClassCastException cce1) {
                    throw new ClassCastException("Transform with owner struct [" + owner.name + "]" +
                        " and cast type from [" + from.name + "] to cast type to [" + to.name + "] using" +
                        " function [" + name + "] cannot cast from type to the function input argument type.");
                }
            }

            final Type rtn = method.rtn;

            try {
                rtn.clazz.asSubclass(to.clazz);
            } catch (final ClassCastException cce0) {
                try {
                    to.clazz.asSubclass(rtn.clazz);
                    downcast = to;
                } catch (final ClassCastException cce1) {
                    throw new ClassCastException("Transform with owner struct [" + owner.name + "]" +
                        " and cast type from [" + from.name + "] to cast type to [" + to.name + "] using" +
                        " function [" + name + "] cannot cast to type to the function return argument type.");
                }
            }
        } else {
            method = owner.methods.get(name);

            if (method == null) {
                throw new IllegalArgumentException("Transform with owner struct [" + owner.name + "]" +
                    " and cast type from [" + from.name + "] to cast type to [" + to.name +
                    "] using a method [" + name + "] that is not defined.");
            }

            if (!method.arguments.isEmpty()) {
                throw new IllegalArgumentException("Transform with owner struct [" + owner.name + "]" +
                    " and cast type from [" + from.name + "] to cast type to [" + to.name +
                    "] using method [" + name + "] does not have a single type argument.");
            }

            try {
                from.clazz.asSubclass(owner.clazz);
            } catch (final ClassCastException cce0) {
                try {
                    owner.clazz.asSubclass(from.clazz);
                    upcast = getType(owner.name);
                } catch (final ClassCastException cce1) {
                    throw new ClassCastException("Transform with owner struct [" + owner.name + "]" +
                        " and cast type from [" + from.name + "] to cast type to [" + to.name + "] using" +
                        " method [" + name + "] cannot cast from type to the method input argument type.");
                }
            }

            final Type rtn = method.rtn;

            try {
                rtn.clazz.asSubclass(to.clazz);
            } catch (final ClassCastException cce0) {
                try {
                    to.clazz.asSubclass(rtn.clazz);
                    downcast = to;
                } catch (final ClassCastException cce1) {
                    throw new ClassCastException("Transform with owner struct [" + owner.name + "]" +
                        " and cast type from [" + from.name + "] to cast type to [" + to.name + "]" +
                        " using method [" + name + "] cannot cast to type to the method return argument type.");
                }
            }
        }

        final Transform transform = new Transform(cast, method, upcast, downcast);
        transformsMap.put(cast, transform);
    }

    /**
     * Precomputes a more efficient structure for dynamic method/field access.
     */
    private void addRuntimeClass(final Struct struct) {
        final Map<String, Method> methods = struct.methods;
        final Map<String, MethodHandle> getters = new HashMap<>();
        final Map<String, MethodHandle> setters = new HashMap<>();

        // add all members
        for (final Map.Entry<String, Field> member : struct.members.entrySet()) {
            getters.put(member.getKey(), member.getValue().getter);
            setters.put(member.getKey(), member.getValue().setter);
        }

        // add all getters/setters
        for (final Map.Entry<String, Method> method : methods.entrySet()) {
            final String name = method.getKey();
            final Method m = method.getValue();

            if (m.arguments.size() == 0 &&
                name.startsWith("get") &&
                name.length() > 3 &&
                Character.isUpperCase(name.charAt(3))) {
                final StringBuilder newName = new StringBuilder();
                newName.append(Character.toLowerCase(name.charAt(3)));
                newName.append(name.substring(4));
                getters.putIfAbsent(newName.toString(), m.handle);
            } else if (m.arguments.size() == 0 &&
                name.startsWith("is") &&
                name.length() > 2 &&
                Character.isUpperCase(name.charAt(2))) {
                final StringBuilder newName = new StringBuilder();
                newName.append(Character.toLowerCase(name.charAt(2)));
                newName.append(name.substring(3));
                getters.putIfAbsent(newName.toString(), m.handle);
            }

            if (m.arguments.size() == 1 &&
                name.startsWith("set") &&
                name.length() > 3 &&
                Character.isUpperCase(name.charAt(3))) {
                final StringBuilder newName = new StringBuilder();
                newName.append(Character.toLowerCase(name.charAt(3)));
                newName.append(name.substring(4));
                setters.putIfAbsent(newName.toString(), m.handle);
            }
        }

        runtimeMap.put(struct.clazz, new RuntimeClass(methods, getters, setters));
    }

    public final Type getType(final String name) {
        final int dimensions = getDimensions(name);
        final String structstr = dimensions == 0 ? name : name.substring(0, name.indexOf('['));
        final Struct struct = structsMap.get(structstr);

        if (struct == null) {
            throw new IllegalArgumentException("The struct with name [" + name + "] has not been defined.");
        }

        return getType(struct, dimensions);
    }

    public final Type getType(final Struct struct, final int dimensions) {
        String name = struct.name;
        org.objectweb.asm.Type type = struct.type;
        Class<?> clazz = struct.clazz;
        Sort sort;

        if (dimensions > 0) {
            final StringBuilder builder = new StringBuilder(name);
            final char[] brackets = new char[dimensions];

            for (int count = 0; count < dimensions; ++count) {
                builder.append("[]");
                brackets[count] = '[';
            }

            final String descriptor = new String(brackets) + struct.type.getDescriptor();

            name = builder.toString();
            type = org.objectweb.asm.Type.getType(descriptor);

            try {
                clazz = Class.forName(type.getInternalName().replace('/', '.'));
            } catch (final ClassNotFoundException exception) {
                throw new IllegalArgumentException("The class [" + type.getInternalName() + "]" +
                    " could not be found to create type [" + name + "].");
            }

            sort = Sort.ARRAY;
        } else if ("def".equals(struct.name)) {
            sort = Sort.DEF;
        } else {
            sort = Sort.OBJECT;

            for (final Sort value : Sort.values()) {
                if (value.clazz == null) {
                    continue;
                }

                if (value.clazz.equals(struct.clazz)) {
                    sort = value;

                    break;
                }
            }
        }

        return new Type(name, dimensions, struct, clazz, type, sort);
    }

    private int getDimensions(final String name) {
        int dimensions = 0;
        int index = name.indexOf('[');

        if (index != -1) {
            final int length = name.length();

            while (index < length) {
                if (name.charAt(index) == '[' && ++index < length && name.charAt(index++) == ']') {
                    ++dimensions;
                } else {
                    throw new IllegalArgumentException("Invalid array braces in canonical name [" + name + "].");
                }
            }
        }

        return dimensions;
    }
}
