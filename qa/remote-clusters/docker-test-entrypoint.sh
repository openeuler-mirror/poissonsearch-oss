#!/bin/bash
cd /usr/share/poissonsearch/bin/
./elasticsearch-users useradd x_pack_rest_user -p x-pack-test-password -r superuser || true
echo "testnode" > /tmp/password
cat /tmp/password  | ./elasticsearch-keystore add -x -f -v 'xpack.security.transport.ssl.keystore.secure_password'
cat /tmp/password  | ./elasticsearch-keystore add -x -f -v 'xpack.security.http.ssl.keystore.secure_password'
/usr/local/bin/docker-entrypoint.sh | tee > /usr/share/poissonsearch/logs/console.log
