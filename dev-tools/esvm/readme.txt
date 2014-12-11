Running ESVM with Shield

Upgrade/Install:
npm install esvm -g

Running:
1) cd to elasticsearch-shield/dev-tools/esvm
2) modify the elasticsearch-license plugin directory in .esvmrc file
3-a) For native users
./esvm
3-b) For openldap users
esvm oldap
3-c) For active directory users
esvm ad

Users and roles are stored in .esvm-shield-config

Troubleshooting:
- elasticsearch is installed under ~/.esvm/<version>
- turn on debug in ~/.esvm/1.4.1/config/logging.yml
- esvm --fresh will reinstall ES
- plugins will not re-install, you can remove them manually by ~/.esvm/1.4.1/bin/plugin --remove shield
- errors during startup will not show up.  If esvm fails startup look in ~/.esvm/1.4.1/logs/*



