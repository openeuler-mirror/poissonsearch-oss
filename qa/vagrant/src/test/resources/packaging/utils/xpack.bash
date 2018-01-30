#!/bin/bash

# Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
# or more contributor license agreements. Licensed under the Elastic License;
# you may not use this file except in compliance with the Elastic License.

install_xpack() {
    install_meta_plugin x-pack
}

# Checks that X-Pack files are correctly installed
verify_xpack_installation() {
    local name="x-pack"
    local user="$ESPLUGIN_COMMAND_USER"
    local group="$ESPLUGIN_COMMAND_USER"

    # Verify binary files
    assert_file "$ESHOME/bin/$name" d $user $group 755
    local binaryFiles=(
        'certgen'
        'certgen.bat'
        'certutil'
        'certutil.bat'
        'croneval'
        'croneval.bat'
        'migrate'
        'migrate.bat'
        'saml-metadata'
        'saml-metadata.bat'
        'setup-passwords'
        'setup-passwords.bat'
        'syskeygen'
        'syskeygen.bat'
        'users'
        'users.bat'
        'x-pack-env'
        'x-pack-env.bat'
        'x-pack-security-env'
        'x-pack-security-env.bat'
        'x-pack-watcher-env'
        'x-pack-watcher-env.bat'
    )

    local binaryFilesCount=0
    for binaryFile in ${binaryFiles[@]}; do
        assert_file "$ESHOME/bin/$name/${binaryFile}" f $user $group 755
        binaryFilesCount=$(( binaryFilesCount + 1 ))
    done
    assert_number_of_files "$ESHOME/bin/$name/" $binaryFilesCount

    # Verify config files
    assert_file "$ESCONFIG/$name" d $user elasticsearch 750
    local configFiles=(
        'users'
        'users_roles'
        'roles.yml'
        'role_mapping.yml'
        'log4j2.properties'
    )

    local configFilesCount=0
    for configFile in ${configFiles[@]}; do
        assert_file "$ESCONFIG/$name/${configFile}" f $user elasticsearch 660
        configFilesCount=$(( configFilesCount + 1 ))
    done
    assert_number_of_files "$ESCONFIG/$name/" $configFilesCount

    # Verify keystore creation
    assert_file "$ESCONFIG/elasticsearch.keystore" f $user elasticsearch 660

    # Read the $name.expected file that contains all the expected
    # plugins for the meta plugin
    while read plugin; do
        assert_module_or_plugin_directory "$ESPLUGINS/$name/$plugin"
        assert_file_exist "$ESPLUGINS/$name/$plugin/$plugin"*".jar"
        assert_file_exist "$ESPLUGINS/$name/$plugin/plugin-descriptor.properties"
        assert_file_exist "$ESPLUGINS/$name/$plugin/plugin-security.policy"
    done </project/build/plugins/$name.expected
}

assert_number_of_files() {
    local directory=$1
    local expected=$2

    local count=$(ls "$directory" | wc -l)
    [ "$count" -eq "$expected" ] || {
        echo "Expected $expected files in $directory but found: $count"
        false
    }
}

wait_for_xpack() {
    local host=${1:-localhost}
    local port=${2:-9200}
    for i in {1..30}; do
        echo "GET / HTTP/1.0" > /dev/tcp/$host/$port && break || sleep 1;
    done
}
