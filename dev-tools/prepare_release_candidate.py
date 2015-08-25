# Licensed to Elasticsearch under one or more contributor
# license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright
# ownership. Elasticsearch licenses this file to you under
# the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance  with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on
# an 'AS IS' BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied. See the License for the specific
# language governing permissions and limitations under the License.

# Prepare a release
#
# 1. Update the Version.java to remove the snapshot bit
# 2. Remove the -SNAPSHOT suffix in all pom.xml files
#
# USAGE:
#
# python3 ./dev-tools/prepare-release.py
#
# Note: Ensure the script is run from the root directory
#

import fnmatch
import argparse
from prepare_release_update_documentation import update_reference_docs
import subprocess
import tempfile
import re
import os
import shutil

VERSION_FILE = 'core/src/main/java/org/elasticsearch/Version.java'
POM_FILE = 'pom.xml'
MAIL_TEMPLATE = """
Hi all

The new release candidate for %(version)s based on this commit[1]  is now available, including the x-plugins, and RPM/deb repos:

   - ZIP [2]
   - tar.gz [3]
   - RPM [4]
   - deb [5]

Plugins can be installed as follows,

    bin/plugin -Des.plugins.staging=true install cloud-aws

The same goes for the x-plugins:

    bin/plugin -Des.plugins.staging=true install license
    bin/plugin -Des.plugins.staging=true install shield
    bin/plugin -Des.plugins.staging=true install watcher

To install the deb from an APT repo:

APT line sources.list line:

deb http://download.elasticsearch.org/elasticsearch/staging/%(version)s-%(hash)s/repos/elasticsearch/%(major_minor_version)s/debian/ stable main

To install the RPM, create a YUM file like:

    /etc/yum.repos.d/elasticsearch.repo

containing:

[elasticsearch-2.0]
name=Elasticsearch repository for packages
baseurl=http://download.elasticsearch.org/elasticsearch/staging/%(version)s-%(hash)s/repos/elasticsearch/%(major_minor_version)s/centos
gpgcheck=1
gpgkey=http://packages.elastic.co/GPG-KEY-elasticsearch
enabled=1


[1] https://github.com/elastic/elasticsearch/commit/%(hash)s
[2] http://download.elasticsearch.org/elasticsearch/staging/%(version)s-%(hash)s/org/elasticsearch/distribution/zip/elasticsearch/%(version)s/elasticsearch-%(version)s.zip
[3] http://download.elasticsearch.org/elasticsearch/staging/%(version)s-%(hash)s/org/elasticsearch/distribution/tar/elasticsearch/%(version)s/elasticsearch-%(version)s.tar.gz
[4] http://download.elasticsearch.org/elasticsearch/staging/%(version)s-%(hash)s/org/elasticsearch/distribution/rpm/elasticsearch/%(version)s/elasticsearch-%(version)s.rpm
[5] http://download.elasticsearch.org/elasticsearch/staging/%(version)s-%(hash)s/org/elasticsearch/distribution/deb/elasticsearch/%(version)s/elasticsearch-%(version)s.deb
"""

def run(command, env_vars=None):
  if env_vars:
    for key, value in env_vars.items():
      os.putenv(key, value)
  if os.system('%s' % (command)):
    raise RuntimeError('    FAILED: %s' % (command))

def ensure_checkout_is_clean():
  # Make sure no local mods:
  s = subprocess.check_output('git diff --shortstat', shell=True).decode('utf-8')
  if len(s) > 0:
    raise RuntimeError('git diff --shortstat is non-empty got:\n%s' % s)

  # Make sure no untracked files:
  s = subprocess.check_output('git status', shell=True).decode('utf-8', errors='replace')
  if 'Untracked files:' in s:
    if 'dev-tools/__pycache__/' in s:
      print('*** NOTE: invoke python with -B to prevent __pycache__ directories ***')
    raise RuntimeError('git status shows untracked files got:\n%s' % s)

  # Make sure we have all changes from origin:
  if 'is behind' in s:
    raise RuntimeError('git status shows not all changes pulled from origin; try running "git pull origin" in this branch got:\n%s' % (s))

  # Make sure we no local unpushed changes (this is supposed to be a clean area):
  if 'is ahead' in s:
    raise RuntimeError('git status shows local commits; try running "git fetch origin", "git checkout ", "git reset --hard origin/" in this branch got:\n%s' % (s))

# Reads the given file and applies the
# callback to it. If the callback changed
# a line the given file is replaced with
# the modified input.
def process_file(file_path, line_callback):
  fh, abs_path = tempfile.mkstemp()
  modified = False
  with open(abs_path,'w', encoding='utf-8') as new_file:
    with open(file_path, encoding='utf-8') as old_file:
      for line in old_file:
        new_line = line_callback(line)
        modified = modified or (new_line != line)
        new_file.write(new_line)
  os.close(fh)
  if modified:
    #Remove original file
    os.remove(file_path)
    #Move new file
    shutil.move(abs_path, file_path)
    return True
  else:
    # nothing to do - just remove the tmp file
    os.remove(abs_path)
    return False

# Moves the Version.java file from a snapshot to a release
def remove_version_snapshot(version_file, release):
  # 1.0.0.Beta1 -> 1_0_0_Beta1
  release = release.replace('.', '_')
  release = release.replace('-', '_')
  pattern = 'new Version(V_%s_ID, true' % (release)
  replacement = 'new Version(V_%s_ID, false' % (release)
  def callback(line):
    return line.replace(pattern, replacement)
  processed = process_file(version_file, callback)
  if not processed:
    raise RuntimeError('failed to remove snapshot version for %s' % (release))

def rename_local_meta_files(path):
  for root, _, file_names in os.walk(path):
    for file_name in fnmatch.filter(file_names, 'maven-metadata-local.xml*'):
      full_path = os.path.join(root, file_name)
      os.rename(full_path, os.path.join(root, file_name.replace('-local', '')))

# Checks the pom.xml for the release version.
# This method fails if the pom file has no SNAPSHOT version set ie.
# if the version is already on a release version we fail.
# Returns the next version string ie. 0.90.7
def find_release_version():
  with open('pom.xml', encoding='utf-8') as file:
    for line in file:
      match = re.search(r'<version>(.+)-SNAPSHOT</version>', line)
      if match:
        return match.group(1)
    raise RuntimeError('Could not find release version in branch')


if __name__ == "__main__":
  parser = argparse.ArgumentParser(description='Builds and publishes a Elasticsearch Release')
  parser.add_argument('--deploy', '-d', dest='deploy', action='store_true',
                      help='Installs and Deploys the release on a sonartype staging repository.')
  parser.add_argument('--skipDocCheck', '-c', dest='skip_doc_check', action='store_false',
                      help='Skips any checks for pending documentation changes')
  parser.add_argument('--push-s3', '-p', dest='push', action='store_true',
                      help='Pushes artifacts to the S3 staging area')
  parser.add_argument('--install_only', '-i', dest='install_only', action='store_true',
                      help='Only runs a maven install to skip the remove deployment step')
  parser.add_argument('--gpg-key', '-k', dest='gpg_key', default="D88E42B4",
                      help='Allows you to specify a different gpg_key to be used instead of the default release key')
  parser.set_defaults(deploy=False)
  parser.set_defaults(skip_doc_check=False)
  parser.set_defaults(push=False)
  parser.set_defaults(install_only=False)
  args = parser.parse_args()
  install_and_deploy = args.deploy
  skip_doc_check = args.skip_doc_check
  push = args.push
  gpg_key = args.gpg_key
  install_only = args.install_only

  ensure_checkout_is_clean()
  release_version = find_release_version()
  if not re.match('(\d+\.\d+)\.*',release_version):
    raise RuntimeError('illegal release version format: %s' % (release_version))
  major_minor_version = re.match('(\d+\.\d+)\.*',release_version).group(1)

  print('*** Preparing release version: [%s]' % release_version)

  if not skip_doc_check:
    print('*** Check for pending documentation changes')
    pending_files = update_reference_docs(release_version)
    if pending_files:
      raise RuntimeError('pending coming[%s] documentation changes found in %s' % (release_version, pending_files))


  run('cd dev-tools && mvn versions:set -DnewVersion=%s -DgenerateBackupPoms=false' % (release_version))
  run('cd rest-api-spec && mvn versions:set -DnewVersion=%s -DgenerateBackupPoms=false' % (release_version))
  run('mvn versions:set -DnewVersion=%s -DgenerateBackupPoms=false' % (release_version))

  remove_version_snapshot(VERSION_FILE, release_version)

  print('*** Done removing snapshot version. DO NOT COMMIT THIS, WHEN CREATING A RELEASE CANDIDATE.')

  shortHash = subprocess.check_output('git log --pretty=format:"%h" -n 1', shell=True).decode('utf-8')
  localRepo = '/tmp/elasticsearch-%s-%s' % (release_version, shortHash)
  localRepoElasticsearch = localRepo + '/org/elasticsearch'
  if os.path.exists(localRepoElasticsearch):
    print('clean local repository %s' % localRepoElasticsearch)
    shutil.rmtree(localRepoElasticsearch)

  if install_only:
    mvn_target = 'install'
  else:
    mvn_target = 'deploy'
  install_command = 'mvn clean %s -Prelease -Dskip.integ.tests=true -Dgpg.keyname="%s" -Dpackaging.rpm.rpmbuild=/usr/bin/rpmbuild -Drpm.sign=true -Dmaven.repo.local=%s -Dno.commit.pattern="\\bno(n|)commit\\b" -Dforbidden.test.signatures=""' % (mvn_target, gpg_key, localRepo)
  clean_repo_command = 'find %s -name _remote.repositories -exec rm {} \;' % (localRepoElasticsearch)
  rename_metadata_files_command = 'for i in $(find %s -name "maven-metadata-local.xml*") ; do mv "$i" "${i/-local/}" ; done' % (localRepoElasticsearch)
  s3_sync_command = 's3cmd sync %s s3://download.elasticsearch.org/elasticsearch/staging/%s-%s/org/' % (localRepoElasticsearch, release_version, shortHash)
  s3_bucket_sync_to = 'download.elasticsearch.org/elasticsearch/staging/%s-%s/repos' % (release_version, shortHash)
  build_repo_command = 'dev-tools/build_repositories.sh %s' % (major_minor_version)
  if install_and_deploy:
    for cmd in [install_command, clean_repo_command]:
      run(cmd)
    rename_local_meta_files(localRepoElasticsearch)
  else:
    print('')
    print('*** To create a release candidate run: ')
    print('  %s' % (install_command))
    print('  1. Remove all _remote.repositories: %s' % (clean_repo_command))
    print('  2. Rename all maven metadata files: %s' % (rename_metadata_files_command))
  if push:
    run(s3_sync_command)
    env_vars = {'S3_BUCKET_SYNC_TO': s3_bucket_sync_to}
    run(build_repo_command, env_vars)
  else:
    print('')
    print('*** To push a release candidate to s3 run: ')
    print('  1. Sync %s into S3 bucket' % (localRepoElasticsearch))
    print ('    %s' % (s3_sync_command))
    print('  2. Create repositories: ')
    print ('    export S3_BUCKET_SYNC_TO="%s"' % (s3_bucket_sync_to))
    print('     %s' % (build_repo_command))
    print('')
    print('NOTE: the above mvn command will promt you several times for the GPG passphrase of the key you specified you can alternatively pass it via -Dgpg.passphrase=yourPassPhrase')
    print(' since RPM signing doesn\'t support gpg-agents the recommended way to set the password is to add a release profile to your settings.xml:')
    print("""
  <profiles>
    <profile>
      <id>release</id>
      <properties>
        <gpg.passphrase>YourPasswordGoesHere</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
    """)
    print('NOTE: Running s3cmd might require you to create a config file with your credentials, if the s3cmd does not support suppliying them via the command line!')
  print('*** Once the release is deployed and published send out the following mail to dev@elastic.co:')
  print(MAIL_TEMPLATE % ({'version' : release_version, 'hash': shortHash, 'major_minor_version' : major_minor_version}))

