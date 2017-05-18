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

package org.elasticsearch.script;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ObjectParser.ValueType;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentParser.Token;
import org.elasticsearch.common.xcontent.XContentType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link Script} represents used-defined input that can be used to
 * compile and execute a script from the {@link ScriptService}
 * based on the {@link ScriptType}.
 *
 * There are three types of scripts specified by {@link ScriptType}.
 *
 * The following describes the expected parameters for each type of script:
 *
 * <ul>
 * <li> {@link ScriptType#INLINE}
 * <ul>
 * <li> {@link Script#lang}     - specifies the language, defaults to {@link Script#DEFAULT_SCRIPT_LANG}
 * <li> {@link Script#idOrCode} - specifies the code to be compiled, must not be {@code null}
 * <li> {@link Script#options}  - specifies the compiler options for this script; must not be {@code null},
 *                                use an empty {@link Map} to specify no options
 * <li> {@link Script#params}   - {@link Map} of user-defined parameters; must not be {@code null},
 *                                use an empty {@link Map} to specify no params
 * </ul>
 * <li> {@link ScriptType#STORED}
 * <ul>
 * <li> {@link Script#lang}     - the language will be specified when storing the script, so this should
 *                                be {@code null}; however, this can be specified to look up a stored
 *                                script as part of the deprecated API
 * <li> {@link Script#idOrCode} - specifies the id of the stored script to be looked up, must not be {@code null}
 * <li> {@link Script#options}  - compiler options will be specified when a stored script is stored,
 *                                so they have no meaning here and must be {@code null}
 * <li> {@link Script#params}   - {@link Map} of user-defined parameters; must not be {@code null},
 *                                use an empty {@link Map} to specify no params
 * </ul>
 * </ul>
 */
public final class Script implements ToXContentObject, Writeable {

    /**
     * Standard logger necessary for allocation of the deprecation logger.
     */
    private static final Logger LOGGER = ESLoggerFactory.getLogger(ScriptMetaData.class);

    /**
     * Deprecation logger necessary for namespace changes related to stored scripts.
     */
    private static final DeprecationLogger DEPRECATION_LOGGER = new DeprecationLogger(LOGGER);

    /**
     * The name of the of the default scripting language.
     */
    public static final String DEFAULT_SCRIPT_LANG = "painless";

    /**
     * The name of the default template language.
     */
    public static final String DEFAULT_TEMPLATE_LANG = "mustache";

    /**
     * The default {@link ScriptType}.
     */
    public static final ScriptType DEFAULT_SCRIPT_TYPE = ScriptType.INLINE;

    /**
     * Compiler option for {@link XContentType} used for templates.
     */
    public static final String CONTENT_TYPE_OPTION = "content_type";

    /**
     * Standard {@link ParseField} for outer level of script queries.
     */
    public static final ParseField SCRIPT_PARSE_FIELD = new ParseField("script");

    /**
     * Standard {@link ParseField} for lang on the inner level.
     */
    public static final ParseField LANG_PARSE_FIELD = new ParseField("lang");

    /**
     * Standard {@link ParseField} for options on the inner level.
     */
    public static final ParseField OPTIONS_PARSE_FIELD = new ParseField("options");

    /**
     * Standard {@link ParseField} for params on the inner level.
     */
    public static final ParseField PARAMS_PARSE_FIELD = new ParseField("params");

    /**
     * Helper class used by {@link ObjectParser} to store mutable {@link Script} variables and then
     * construct an immutable {@link Script} object based on parsed XContent.
     */
    private static final class Builder {
        private ScriptType type;
        private String lang;
        private String idOrCode;
        private Map<String, String> options;
        private Map<String, Object> params;

        private Builder() {
            // This cannot default to an empty map because options are potentially added at multiple points.
            this.options = new HashMap<>();
            this.params = Collections.emptyMap();
        }

        /**
         * Since inline scripts can accept code rather than just an id, they must also be able
         * to handle template parsing, hence the need for custom parsing code.  Templates can
         * consist of either an {@link String} or a JSON object.  If a JSON object is discovered
         * then the content type option must also be saved as a compiler option.
         */
        private void setInline(XContentParser parser) {
            try {
                if (type != null) {
                    throwOnlyOneOfType();
                }

                type = ScriptType.INLINE;

                if (parser.currentToken() == Token.START_OBJECT) {
                    //this is really for search templates, that need to be converted to json format
                    XContentBuilder builder = XContentFactory.jsonBuilder();
                    idOrCode = builder.copyCurrentStructure(parser).string();
                    options.put(CONTENT_TYPE_OPTION, XContentType.JSON.mediaType());
                } else {
                    idOrCode = parser.text();
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        /**
         * Set both the id and the type of the stored script.
         */
        private void setStored(String idOrCode) {
            if (type != null) {
                throwOnlyOneOfType();
            }

            type = ScriptType.STORED;
            this.idOrCode = idOrCode;
        }

        /**
         * Helper method to throw an exception if more than one type of {@link Script} is specified.
         */
        private void throwOnlyOneOfType() {
            throw new IllegalArgumentException("must only use one of [" +
                ScriptType.INLINE.getParseField().getPreferredName() + ", " +
                ScriptType.STORED.getParseField().getPreferredName() + "]" +
                " when specifying a script");
        }

        private void setLang(String lang) {
            this.lang = lang;
        }

        /**
         * Options may have already been added if an inline template was specified.
         * Appends the user-defined compiler options with the internal compiler options.
         */
        private void setOptions(Map<String, String> options) {
            this.options.putAll(options);
        }

        private void setParams(Map<String, Object> params) {
            this.params = params;
        }

        /**
         * Validates the parameters and creates an {@link Script}.
         * @param defaultLang The default lang is not a compile-time constant and must be provided
         *                    at run-time this way in case a legacy default language is used from
         *                    previously stored queries.
         */
        private Script build(String defaultLang) {
            if (type == null) {
                throw new IllegalArgumentException(
                    "must specify either code for an [" + ScriptType.INLINE.getParseField().getPreferredName() + "] script " +
                        "or an id for a [" + ScriptType.STORED.getParseField().getPreferredName() + "] script");
            }

            if (type == ScriptType.INLINE) {
                if (lang == null) {
                    lang = defaultLang;
                }

                if (idOrCode == null) {
                    throw new IllegalArgumentException(
                        "must specify <id> for an [" + ScriptType.INLINE.getParseField().getPreferredName() + "] script");
                }

                if (options.size() > 1 || options.size() == 1 && options.get(CONTENT_TYPE_OPTION) == null) {
                    options.remove(CONTENT_TYPE_OPTION);

                    throw new IllegalArgumentException("illegal compiler options [" + options + "] specified");
                }
            } else if (type == ScriptType.STORED) {
                // Only issue this deprecation warning if we aren't using a template.  Templates during
                // this deprecation phase must always specify the default template language or they would
                // possibly pick up a script in a different language as defined by the user under the new
                // namespace unintentionally.
                if (lang != null && lang.equals(DEFAULT_TEMPLATE_LANG) == false) {
                    DEPRECATION_LOGGER.deprecated("specifying the field [" + LANG_PARSE_FIELD.getPreferredName() + "] " +
                        "for executing " + ScriptType.STORED + " scripts is deprecated; use only the field " +
                        "[" + ScriptType.STORED.getParseField().getPreferredName() + "] to specify an <id>");
                }

                if (idOrCode == null) {
                    throw new IllegalArgumentException(
                        "must specify <code> for an [" + ScriptType.STORED.getParseField().getPreferredName() + "] script");
                }

                if (options.isEmpty()) {
                    options = null;
                } else {
                    throw new IllegalArgumentException("field [" + OPTIONS_PARSE_FIELD.getPreferredName() + "] " +
                        "cannot be specified using a [" + ScriptType.STORED.getParseField().getPreferredName() + "] script");
                }
            }

            return new Script(type, lang, idOrCode, options, params);
        }
    }

    private static final ObjectParser<Builder, Void> PARSER = new ObjectParser<>("script", Builder::new);

    static {
        // Defines the fields necessary to parse a Script as XContent using an ObjectParser.
        PARSER.declareField(Builder::setInline, parser -> parser, ScriptType.INLINE.getParseField(), ValueType.OBJECT_OR_STRING);
        PARSER.declareString(Builder::setStored, ScriptType.STORED.getParseField());
        PARSER.declareString(Builder::setLang, LANG_PARSE_FIELD);
        PARSER.declareField(Builder::setOptions, XContentParser::mapStrings, OPTIONS_PARSE_FIELD, ValueType.OBJECT);
        PARSER.declareField(Builder::setParams, XContentParser::map, PARAMS_PARSE_FIELD, ValueType.OBJECT);
    }

    /**
     * Convenience method to call {@link Script#parse(XContentParser, String)}
     * using the default scripting language.
     */
    public static Script parse(XContentParser parser) throws IOException {
        return parse(parser, DEFAULT_SCRIPT_LANG);
    }

    /**
     * This will parse XContent into a {@link Script}.  The following formats can be parsed:
     *
     * The simple format defaults to an {@link ScriptType#INLINE} with no compiler options or user-defined params:
     *
     * Example:
     * {@code
     * "return Math.log(doc.popularity) * 100;"
     * }
     *
     * The complex format where {@link ScriptType} and idOrCode are required while lang, options and params are not required.
     *
     * {@code
     * {
     *     "<type (inline, stored, file)>" : "<idOrCode>",
     *     "lang" : "<lang>",
     *     "options" : {
     *         "option0" : "<option0>",
     *         "option1" : "<option1>",
     *         ...
     *     },
     *     "params" : {
     *         "param0" : "<param0>",
     *         "param1" : "<param1>",
     *         ...
     *     }
     * }
     * }
     *
     * Example:
     * {@code
     * {
     *     "inline" : "return Math.log(doc.popularity) * params.multiplier",
     *     "lang" : "painless",
     *     "params" : {
     *         "multiplier" : 100.0
     *     }
     * }
     * }
     *
     * This also handles templates in a special way.  If a complexly formatted query is specified as another complex
     * JSON object the query is assumed to be a template, and the format will be preserved.
     *
     * {@code
     * {
     *     "inline" : { "query" : ... },
     *     "lang" : "<lang>",
     *     "options" : {
     *         "option0" : "<option0>",
     *         "option1" : "<option1>",
     *         ...
     *     },
     *     "params" : {
     *         "param0" : "<param0>",
     *         "param1" : "<param1>",
     *         ...
     *     }
     * }
     * }
     *
     * @param parser       The {@link XContentParser} to be used.
     * @param defaultLang  The default language to use if no language is specified.  The default language isn't necessarily
     *                     the one defined by {@link Script#DEFAULT_SCRIPT_LANG} due to backwards compatibility requirements
     *                     related to stored queries using previously default languages.
     *
     * @return             The parsed {@link Script}.
     */
    public static Script parse(XContentParser parser, String defaultLang) throws IOException {
        Objects.requireNonNull(defaultLang);

        Token token = parser.currentToken();

        if (token == null) {
            token = parser.nextToken();
        }

        if (token == Token.VALUE_STRING) {
            return new Script(ScriptType.INLINE, defaultLang, parser.text(), Collections.emptyMap());
        }

        return PARSER.apply(parser, null).build(defaultLang);
    }

    private final ScriptType type;
    private final String lang;
    private final String idOrCode;
    private final Map<String, String> options;
    private final Map<String, Object> params;

    /**
     * Constructor for simple script using the default language and default type.
     * @param idOrCode The id or code to use dependent on the default script type.
     */
    public Script(String idOrCode) {
        this(DEFAULT_SCRIPT_TYPE, DEFAULT_SCRIPT_LANG, idOrCode, Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * Constructor for a script that does not need to use compiler options.
     * @param type     The {@link ScriptType}.
     * @param lang     The language for this {@link Script} if the {@link ScriptType} is {@link ScriptType#INLINE}.
     *                 For {@link ScriptType#STORED} scripts this should be null, but can
     *                 be specified to access scripts stored as part of the stored scripts deprecated API.
     * @param idOrCode The id for this {@link Script} if the {@link ScriptType} is {@link ScriptType#STORED}.
     *                 The code for this {@link Script} if the {@link ScriptType} is {@link ScriptType#INLINE}.
     * @param params   The user-defined params to be bound for script execution.
     */
    public Script(ScriptType type, String lang, String idOrCode, Map<String, Object> params) {
        this(type, lang, idOrCode, type == ScriptType.INLINE ? Collections.emptyMap() : null, params);
    }

    /**
     * Constructor for a script that requires the use of compiler options.
     * @param type     The {@link ScriptType}.
     * @param lang     The language for this {@link Script} if the {@link ScriptType} is {@link ScriptType#INLINE}.
     *                 For {@link ScriptType#STORED} scripts this should be null, but can
     *                 be specified to access scripts stored as part of the stored scripts deprecated API.
     * @param idOrCode The id for this {@link Script} if the {@link ScriptType} is {@link ScriptType#STORED}.
     *                 The code for this {@link Script} if the {@link ScriptType} is {@link ScriptType#INLINE}.
     * @param options  The map of compiler options for this {@link Script} if the {@link ScriptType}
     *                 is {@link ScriptType#INLINE}, {@code null} otherwise.
     * @param params   The user-defined params to be bound for script execution.
     */
    public Script(ScriptType type, String lang, String idOrCode, Map<String, String> options, Map<String, Object> params) {
        this.type = Objects.requireNonNull(type);
        this.idOrCode = Objects.requireNonNull(idOrCode);
        this.params = Collections.unmodifiableMap(Objects.requireNonNull(params));

        if (type == ScriptType.INLINE) {
            this.lang = Objects.requireNonNull(lang);
            this.options = Collections.unmodifiableMap(Objects.requireNonNull(options));
        } else if (type == ScriptType.STORED) {
            this.lang = lang;

            if (options != null) {
                throw new IllegalStateException(
                    "options must be null for [" + ScriptType.STORED.getParseField().getPreferredName() + "] scripts");
            }

            this.options = null;
        } else {
            throw new IllegalStateException("unknown script type [" + type.getName() + "]");
        }
    }

    /**
     * Creates a {@link Script} read from an input stream.
     */
    public Script(StreamInput in) throws IOException {
        // Version 5.3 allows lang to be an optional parameter for stored scripts and expects
        // options to be null for stored and file scripts.
        if (in.getVersion().onOrAfter(Version.V_5_3_0)) {
            this.type = ScriptType.readFrom(in);
            this.lang = in.readOptionalString();
            this.idOrCode = in.readString();
            @SuppressWarnings("unchecked")
            Map<String, String> options = (Map)in.readMap();
            this.options = options;
            this.params = in.readMap();
        // Version 5.1 to 5.3 (exclusive) requires all Script members to be non-null and supports the potential
        // for more options than just XContentType.  Reorders the read in contents to be in
        // same order as the constructor.
        } else if (in.getVersion().onOrAfter(Version.V_5_1_1)) {
            this.type = ScriptType.readFrom(in);
            this.lang = in.readString();

            this.idOrCode = in.readString();
            @SuppressWarnings("unchecked")
            Map<String, String> options = (Map)in.readMap();

            if (this.type != ScriptType.INLINE && options.isEmpty()) {
                this.options = null;
            } else {
                this.options = options;
            }

            this.params = in.readMap();
        // Prior to version 5.1 the script members are read in certain cases as optional and given
        // default values when necessary.  Also the only option supported is for XContentType.
        } else {
            this.idOrCode = in.readString();

            if (in.readBoolean()) {
                this.type = ScriptType.readFrom(in);
            } else {
                this.type = DEFAULT_SCRIPT_TYPE;
            }

            String lang = in.readOptionalString();

            if (lang == null) {
                this.lang = DEFAULT_SCRIPT_LANG;
            } else {
                this.lang = lang;
            }

            Map<String, Object> params = in.readMap();

            if (params == null) {
                this.params = new HashMap<>();
            } else {
                this.params = params;
            }

            if (in.readBoolean()) {
                this.options = new HashMap<>();
                XContentType contentType = XContentType.readFrom(in);
                this.options.put(CONTENT_TYPE_OPTION, contentType.mediaType());
            } else if (type == ScriptType.INLINE) {
                options = new HashMap<>();
            } else {
                this.options = null;
            }
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        // Version 5.3+ allows lang to be an optional parameter for stored scripts and expects
        // options to be null for stored and file scripts.
        if (out.getVersion().onOrAfter(Version.V_5_3_0)) {
            type.writeTo(out);
            out.writeOptionalString(lang);
            out.writeString(idOrCode);
            @SuppressWarnings("unchecked")
            Map<String, Object> options = (Map)this.options;
            out.writeMap(options);
            out.writeMap(params);
        // Version 5.1 to 5.3 (exclusive) requires all Script members to be non-null and supports the potential
        // for more options than just XContentType.  Reorders the written out contents to be in
        // same order as the constructor.
        } else if (out.getVersion().onOrAfter(Version.V_5_1_1)) {
            type.writeTo(out);

            if (lang == null) {
                out.writeString("");
            } else {
                out.writeString(lang);
            }

            out.writeString(idOrCode);
            @SuppressWarnings("unchecked")
            Map<String, Object> options = (Map)this.options;

            if (options == null) {
                out.writeMap(new HashMap<>());
            } else {
                out.writeMap(options);
            }

            out.writeMap(params);
        // Prior to version 5.1 the Script members were possibly written as optional or null, though there is no case where a null
        // value wasn't equivalent to it's default value when actually compiling/executing a script.  Meaning, there are no
        // backwards compatibility issues, and now there's enforced consistency.  Also the only supported compiler
        // option was XContentType.
        } else {
            out.writeString(idOrCode);
            out.writeBoolean(true);
            type.writeTo(out);
            out.writeOptionalString(lang);

            if (params.isEmpty()) {
                out.writeMap(null);
            } else {
                out.writeMap(params);
            }

            if (options != null && options.containsKey(CONTENT_TYPE_OPTION)) {
                XContentType contentType = XContentType.fromMediaTypeOrFormat(options.get(CONTENT_TYPE_OPTION));
                out.writeBoolean(true);
                contentType.writeTo(out);
            } else {
                out.writeBoolean(false);
            }
        }
    }

    /**
     * This will build scripts into the following XContent structure:
     *
     * {@code
     * {
     *     "<type (inline, stored, file)>" : "<idOrCode>",
     *     "lang" : "<lang>",
     *     "options" : {
     *         "option0" : "<option0>",
     *         "option1" : "<option1>",
     *         ...
     *     },
     *     "params" : {
     *         "param0" : "<param0>",
     *         "param1" : "<param1>",
     *         ...
     *     }
     * }
     * }
     *
     * Example:
     * {@code
     * {
     *     "inline" : "return Math.log(doc.popularity) * params.multiplier;",
     *     "lang" : "painless",
     *     "params" : {
     *         "multiplier" : 100.0
     *     }
     * }
     * }
     *
     * Note that lang, options, and params will only be included if there have been any specified.
     *
     * This also handles templates in a special way.  If the {@link Script#CONTENT_TYPE_OPTION} option
     * is provided and the {@link ScriptType#INLINE} is specified then the template will be preserved as a raw field.
     *
     * {@code
     * {
     *     "inline" : { "query" : ... },
     *     "lang" : "<lang>",
     *     "options" : {
     *         "option0" : "<option0>",
     *         "option1" : "<option1>",
     *         ...
     *     },
     *     "params" : {
     *         "param0" : "<param0>",
     *         "param1" : "<param1>",
     *         ...
     *     }
     * }
     * }
     */
    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params builderParams) throws IOException {
        builder.startObject();

        String contentType = options == null ? null : options.get(CONTENT_TYPE_OPTION);

        if (type == ScriptType.INLINE && contentType != null && builder.contentType().mediaType().equals(contentType)) {
            builder.rawField(type.getParseField().getPreferredName(), new BytesArray(idOrCode));
        } else {
            builder.field(type.getParseField().getPreferredName(), idOrCode);
        }

        if (lang != null) {
            builder.field(LANG_PARSE_FIELD.getPreferredName(), lang);
        }

        if (options != null && !options.isEmpty()) {
            builder.field(OPTIONS_PARSE_FIELD.getPreferredName(), options);
        }

        if (!params.isEmpty()) {
            builder.field(PARAMS_PARSE_FIELD.getPreferredName(), params);
        }

        builder.endObject();

        return builder;
    }

    /**
     * @return The {@link ScriptType} for this {@link Script}.
     */
    public ScriptType getType() {
        return type;
    }

    /**
     * @return The language for this {@link Script} if the {@link ScriptType} is {@link ScriptType#INLINE}.
     *         For {@link ScriptType#STORED} scripts this should be null, but can
     *         be specified to access scripts stored as part of the stored scripts deprecated API.
     */
    public String getLang() {
        return lang;
    }

    /**
     * @return The id for this {@link Script} if the {@link ScriptType} is {@link ScriptType#STORED}.
     *         The code for this {@link Script} if the {@link ScriptType} is {@link ScriptType#INLINE}.
     */
    public String getIdOrCode() {
        return idOrCode;
    }

    /**
     * @return The map of compiler options for this {@link Script} if the {@link ScriptType}
     *         is {@link ScriptType#INLINE}, {@code null} otherwise.
     */
    public Map<String, String> getOptions() {
        return options;
    }

    /**
     * @return The map of user-defined params for this {@link Script}.
     */
    public Map<String, Object> getParams() {
        return params;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Script script = (Script)o;

        if (type != script.type) return false;
        if (lang != null ? !lang.equals(script.lang) : script.lang != null) return false;
        if (!idOrCode.equals(script.idOrCode)) return false;
        if (options != null ? !options.equals(script.options) : script.options != null) return false;
        return params.equals(script.params);

    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + (lang != null ? lang.hashCode() : 0);
        result = 31 * result + idOrCode.hashCode();
        result = 31 * result + (options != null ? options.hashCode() : 0);
        result = 31 * result + params.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Script{" +
            "type=" + type +
            ", lang='" + lang + '\'' +
            ", idOrCode='" + idOrCode + '\'' +
            ", options=" + options +
            ", params=" + params +
            '}';
    }
}
