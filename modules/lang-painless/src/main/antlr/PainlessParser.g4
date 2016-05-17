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

parser grammar PainlessParser;

options { tokenVocab=PainlessLexer; }

source
    : statement+ EOF
    ;

statement
    : IF LP expression RP block ( ELSE block )?                                              # if
    | WHILE LP expression RP ( block | empty )                                               # while
    | DO block WHILE LP expression RP ( SEMICOLON | EOF )                                    # do
    | FOR LP initializer? SEMICOLON expression? SEMICOLON afterthought? RP ( block | empty ) # for
    | declaration ( SEMICOLON | EOF )                                                        # decl
    | CONTINUE ( SEMICOLON | EOF )                                                           # continue
    | BREAK ( SEMICOLON | EOF )                                                              # break
    | RETURN expression ( SEMICOLON | EOF )                                                  # return
    | TRY block trap+                                                                        # try
    | THROW expression ( SEMICOLON | EOF )                                                   # throw
    | expression ( SEMICOLON | EOF )                                                         # expr
    ;

block
    : LBRACK statement+ RBRACK                 # multiple
    | statement                                # single
    ;

empty
    : emptyscope
    | SEMICOLON
    ;

emptyscope
    : LBRACK RBRACK
    ;

initializer
    : declaration
    | expression
    ;

afterthought
    : expression
    ;

declaration
    : decltype declvar ( COMMA declvar )*
    ;

decltype
    : identifier (LBRACE RBRACE)*
    ;

declvar
    : identifier ( ASSIGN expression )?
    ;

trap
    : CATCH LP ( identifier identifier ) RP ( block | emptyscope )
    ;

identifier
    : ID generic?
    ;

generic
    : LT identifier ( COMMA identifier )* GT
    ;

expression
    :               LP expression RP                                    # precedence
    |               ( OCTAL | HEX | INTEGER | DECIMAL )                 # numeric
    |               TRUE                                                # true
    |               FALSE                                               # false
    |               NULL                                                # null
    | <assoc=right> chain ( INCR | DECR )                               # postinc
    | <assoc=right> ( INCR | DECR ) chain                               # preinc
    |               chain                                               # read
    | <assoc=right> ( BOOLNOT | BWNOT | ADD | SUB ) expression          # unary
    | <assoc=right> LP decltype RP expression                           # cast
    |               expression ( MUL | DIV | REM ) expression           # binary
    |               expression ( ADD | SUB ) expression                 # binary
    |               expression ( LSH | RSH | USH ) expression           # binary
    |               expression ( LT | LTE | GT | GTE ) expression       # comp
    |               expression ( EQ | EQR | NE | NER ) expression       # comp
    |               expression BWAND expression                         # binary
    |               expression XOR expression                           # binary
    |               expression BWOR expression                          # binary
    |               expression BOOLAND expression                       # bool
    |               expression BOOLOR expression                        # bool
    | <assoc=right> expression COND expression COLON expression         # conditional
    | <assoc=right> chain ( ASSIGN | AADD | ASUB | AMUL | ADIV
                                      | AREM | AAND | AXOR | AOR
                                      | ALSH | ARSH | AUSH ) expression # assignment
    ;

chain
    : linkprec
    | linkcast
    | linkvar
    | linknew
    | linkstring
    ;

linkprec:   LP ( linkprec | linkcast | linkvar | linknew | linkstring ) RP ( linkdot | linkbrace )?;
linkcast:   LP decltype RP ( linkprec | linkcast | linkvar | linknew | linkstring );
linkbrace:  LBRACE expression RBRACE ( linkdot | linkbrace )?;
linkdot:    DOT ( linkcall | linkfield );
linkcall:   EXTID arguments ( linkdot | linkbrace )?;
linkvar:    identifier ( linkdot | linkbrace )?;
linkfield:  ( EXTID | EXTINTEGER ) ( linkdot | linkbrace )?;
linknew:    NEW identifier ( ( arguments linkdot? ) | ( ( LBRACE expression RBRACE )+ linkdot? ) );
linkstring: STRING (linkdot | linkbrace )?;

arguments
    : ( LP ( expression ( COMMA expression )* )? RP )
    ;

