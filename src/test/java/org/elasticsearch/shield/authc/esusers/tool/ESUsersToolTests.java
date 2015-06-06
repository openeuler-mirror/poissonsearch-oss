/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.esusers.tool;

import com.google.common.base.Charsets;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.cli.CliTool;
import org.elasticsearch.common.cli.CliToolTestCase;
import org.elasticsearch.common.cli.Terminal;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.shield.authc.esusers.FileUserRolesStore;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.SecuredStringTests;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.*;

/**
 *
 */
public class ESUsersToolTests extends CliToolTestCase {

    @Test
    public void testUseradd_Parse_AllOptions() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("useradd", args("username -p changeme -r r1,r2,r3"));
        assertThat(command, instanceOf(ESUsersTool.Useradd.class));
        ESUsersTool.Useradd cmd = (ESUsersTool.Useradd) command;
        assertThat(cmd.username, equalTo("username"));
        assertThat(new String(cmd.passwd.internalChars()), equalTo("changeme"));
        assertThat(cmd.roles, notNullValue());
        assertThat(cmd.roles, arrayContaining("r1", "r2", "r3"));
    }

    @Test
    public void testUseraddExtraArgs() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("useradd", args("username -p changeme -r r1,r2,r3 r4 r6"));
        assertThat(command, instanceOf(CliTool.Command.Exit.class));
        CliTool.Command.Exit exit = (CliTool.Command.Exit) command;
        assertThat(exit.status(), is(CliTool.ExitStatus.USAGE));
    }

    @Test
    public void testUseradd_Parse_InvalidUsername() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("useradd", args("$34dkl -p changeme -r r1,r2,r3"));
        assertThat(command, instanceOf(CliTool.Command.Exit.class));
        CliTool.Command.Exit exit = (CliTool.Command.Exit) command;
        assertThat(exit.status(), is(CliTool.ExitStatus.DATA_ERROR));
    }

    @Test
    public void testUseradd_Parse_InvalidRoleName() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("useradd", args("username -p changeme -r $343,r2,r3"));
        assertThat(command, instanceOf(CliTool.Command.Exit.class));
        CliTool.Command.Exit exit = (CliTool.Command.Exit) command;
        assertThat(exit.status(), is(CliTool.ExitStatus.DATA_ERROR));
    }

    @Test
    public void testUseradd_Parse_InvalidPassword() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("useradd", args("username -p 123 -r r1,r2,r3"));
        assertThat(command, instanceOf(CliTool.Command.Exit.class));
        CliTool.Command.Exit exit = (CliTool.Command.Exit) command;
        assertThat(exit.status(), is(CliTool.ExitStatus.DATA_ERROR));
    }

    @Test
    public void testUseradd_Parse_NoUsername() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("useradd", args("-p test123"));
        assertThat(command, instanceOf(CliTool.Command.Exit.class));
        assertThat(((CliTool.Command.Exit) command).status(), is(CliTool.ExitStatus.USAGE));
    }

    @Test
    public void testUseradd_Parse_NoPassword() throws Exception {
        ESUsersTool tool = new ESUsersTool(new MockTerminal() {
            @Override
            public char[] readSecret(String text, Object... args) {
                return "changeme".toCharArray();
            }
        });
        CliTool.Command command = tool.parse("useradd", args("username"));
        assertThat(command, instanceOf(ESUsersTool.Useradd.class));
        ESUsersTool.Useradd cmd = (ESUsersTool.Useradd) command;
        assertThat(cmd.username, equalTo("username"));
        assertThat(new String(cmd.passwd.internalChars()), equalTo("changeme"));
        assertThat(cmd.roles, notNullValue());
        assertThat(cmd.roles.length, is(0));
    }

    @Test
    public void testUseradd_Cmd_Create() throws Exception {
        Path userFile = createTempFile();
        Path userRolesFile = createTempFile();
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", userFile)
                .put("shield.authc.realms.esusers.files.users_roles", userRolesFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Useradd cmd = new ESUsersTool.Useradd(new MockTerminal(), "user1", SecuredStringTests.build("changeme"), "r1", "r2");

        CliTool.ExitStatus status = execute(cmd, settings);
        assertThat(status, is(CliTool.ExitStatus.OK));

        assertFileExists(userFile);
        List<String> lines = Files.readAllLines(userFile, Charsets.UTF_8);
        assertThat(lines.size(), is(1));
        // we can't just hash again and compare the lines, as every time we hash a new salt is generated
        // instead we'll just verify the generated hash against the correct password.
        String line = lines.get(0);
        assertThat(line, startsWith("user1:"));
        String hash = line.substring("user1:".length());
        assertThat(Hasher.BCRYPT.verify(SecuredStringTests.build("changeme"), hash.toCharArray()), is(true));

        assertFileExists(userRolesFile);
        lines = Files.readAllLines(userRolesFile, Charsets.UTF_8);
        assertThat(lines, hasSize(2));
        assertThat(lines, containsInAnyOrder("r1:user1", "r2:user1"));
    }

    @Test
    public void testUseradd_Cmd_Append() throws Exception {
        Path userFile = writeFile("user2:hash2");
        Path userRolesFile = writeFile("r3:user2\nr4:user2");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", userFile)
                .put("shield.authc.realms.esusers.files.users_roles", userRolesFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Useradd cmd = new ESUsersTool.Useradd(new MockTerminal(), "user1", SecuredStringTests.build("changeme"), "r1", "r2");

        CliTool.ExitStatus status = execute(cmd, settings);
        assertThat(status, is(CliTool.ExitStatus.OK));

        assertFileExists(userFile);
        List<String> lines = Files.readAllLines(userFile, Charsets.UTF_8);
        assertThat(lines, hasSize(2));
        assertThat(lines, hasItem("user2:hash2"));
        assertThat(lines, hasItem(startsWith("user1:")));

        // we can't just hash again and compare the lines, as every time we hash a new salt is generated
        // instead we'll just verify the generated hash against the correct password.
        for (String line : lines) {
            if (line.startsWith("user1")) {
                String hash = line.substring("user1:".length());
                assertThat(Hasher.BCRYPT.verify(SecuredStringTests.build("changeme"), hash.toCharArray()), is(true));
            }
        }

        assertFileExists(userRolesFile);
        lines = Files.readAllLines(userRolesFile, Charsets.UTF_8);
        assertThat(lines, hasSize(4));
        assertThat(lines, containsInAnyOrder("r1:user1", "r2:user1", "r3:user2", "r4:user2"));
    }

    @Test
    public void testUseradd_Cmd_AddingUserWithoutRolesDoesNotAddEmptyRole() throws Exception {
        Path userFile = writeFile("user2:hash2");
        Path userRolesFile = writeFile("r3:user2\nr4:user2");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", userFile)
                .put("shield.authc.realms.esusers.files.users_roles", userRolesFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Useradd cmd = new ESUsersTool.Useradd(new MockTerminal(), "user1", SecuredStringTests.build("changeme"));

        CliTool.ExitStatus status = execute(cmd, settings);
        assertThat(status, is(CliTool.ExitStatus.OK));

        assertFileExists(userRolesFile);
        List<String> lines = Files.readAllLines(userRolesFile, Charsets.UTF_8);
        assertThat(lines, hasSize(2));
        assertThat(lines, not(hasItem(containsString("user1"))));
    }

    @Test
    public void testUseradd_Cmd_Append_UserAlreadyExists() throws Exception {
        Path userFile = writeFile("user1:hash1");
        Path userRolesFile = createTempFile();
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", userFile)
                .put("shield.authc.realms.esusers.files.users_roles", userRolesFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Useradd cmd = new ESUsersTool.Useradd(new MockTerminal(), "user1", SecuredStringTests.build("changeme"), "r1", "r2");

        CliTool.ExitStatus status = execute(cmd, settings);
        assertThat(status, is(CliTool.ExitStatus.CODE_ERROR));
    }


    @Test
    public void testUseradd_CustomRole() throws Exception {
        Path usersFile = createTempFile();
        Path userRolesFile = createTempFile();
        Path rolesFile = writeFile("plugin_admin:\n" +
                "  manage_plugin");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authc.realms.esusers.files.users_roles", userRolesFile)
                .put("shield.authz.store.files.roles", rolesFile)
                .put("path.home", createTempDir())
                .build();

        final CaptureOutputTerminal terminal = new CaptureOutputTerminal();
        ESUsersTool.Useradd cmd = new ESUsersTool.Useradd(terminal, "user1", SecuredStringTests.build("changeme"), "plugin_admin");

        CliTool.ExitStatus status = execute(cmd, settings);
        assertThat(status, is(CliTool.ExitStatus.OK));
        assertThat(terminal.getTerminalOutput(), hasSize(0));
    }

    @Test
    public void testUseradd_NonExistantRole() throws Exception {
        Path usersFile = createTempFile();
        Path userRolesFile = createTempFile();
        Path rolesFile = writeFile("plugin_admin:\n" +
                "  manage_plugin");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authc.realms.esusers.files.users_roles", userRolesFile)
                .put("shield.authz.store.files.roles", rolesFile)
                .put("path.home", createTempDir())
                .build();

        final CaptureOutputTerminal terminal = new CaptureOutputTerminal();
        ESUsersTool.Useradd cmd = new ESUsersTool.Useradd(terminal, "user1", SecuredStringTests.build("changeme"), "plugin_admin_2");

        CliTool.ExitStatus status = execute(cmd, settings);
        assertThat(status, is(CliTool.ExitStatus.OK));
        assertThat(terminal.getTerminalOutput(), hasSize(1));
        assertThat(terminal.getTerminalOutput().get(0), containsString("[plugin_admin_2]"));
    }

    @Test
    public void testUserdel_Parse() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("userdel", args("username"));
        assertThat(command, instanceOf(ESUsersTool.Userdel.class));
        ESUsersTool.Userdel userdel = (ESUsersTool.Userdel) command;
        assertThat(userdel.username, equalTo("username"));
    }

    @Test
    public void testUserdel_Parse_MissingUsername() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("userdel", args(null));
        assertThat(command, instanceOf(ESUsersTool.Command.Exit.class));
        ESUsersTool.Command.Exit exit = (ESUsersTool.Command.Exit) command;
        assertThat(exit.status(), equalTo(CliTool.ExitStatus.USAGE));
    }

    @Test
    public void testUserdel_Parse_ExtraArgs() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("userdel", args("user1 user2"));
        assertThat(command, instanceOf(ESUsersTool.Command.Exit.class));
        ESUsersTool.Command.Exit exit = (ESUsersTool.Command.Exit) command;
        assertThat(exit.status(), equalTo(CliTool.ExitStatus.USAGE));
    }

    @Test
    public void testUserdel_Cmd() throws Exception {
        Path userFile = writeFile("user1:hash2");
        Path userRolesFile = writeFile("r3:user1\nr4:user1");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", userFile)
                .put("shield.authc.realms.esusers.files.users_roles", userRolesFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Userdel cmd = new ESUsersTool.Userdel(new MockTerminal(), "user1");

        CliTool.ExitStatus status = execute(cmd, settings);
        assertThat(status, is(CliTool.ExitStatus.OK));

        assertFileExists(userFile);
        List<String> lines = Files.readAllLines(userFile, Charsets.UTF_8);
        assertThat(lines.size(), is(0));

        assertFileExists(userRolesFile);
        lines = Files.readAllLines(userRolesFile, Charsets.UTF_8);
        assertThat(lines.size(), is(0));
    }

    @Test
    public void testUserdel_Cmd_MissingUser() throws Exception {
        Path userFile = writeFile("user1:hash2");
        Path userRolesFile = writeFile("r3:user1\nr4:user1");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", userFile)
                .put("shield.authc.realms.esusers.files.users_roles", userRolesFile)
                .put("path.home", createTempDir())
                .build();
        CaptureOutputTerminal terminal = new CaptureOutputTerminal();

        ESUsersTool.Userdel cmd = new ESUsersTool.Userdel(terminal, "user2");

        CliTool.ExitStatus status = execute(cmd, settings);
        assertThat(status, is(CliTool.ExitStatus.NO_USER));

        List<String> output = terminal.getTerminalOutput();
        assertThat(output, hasSize(equalTo(1)));
        assertThat(output, hasItem(startsWith("User [user2] doesn't exist")));

        assertFileExists(userFile);
        List<String> lines = Files.readAllLines(userFile, Charsets.UTF_8);
        assertThat(lines.size(), is(1));

        assertFileExists(userRolesFile);
        lines = Files.readAllLines(userRolesFile, Charsets.UTF_8);
        assertThat(lines, hasSize(2));
    }

    @Test
    public void testUserdel_Cmd_MissingFiles() throws Exception {
        Path dir = createTempDir();
        Path userFile = dir.resolve("users");
        Path userRolesFile = dir.resolve("users_roles");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", userFile)
                .put("shield.authc.realms.esusers.files.users_roles", userRolesFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Userdel cmd = new ESUsersTool.Userdel(new MockTerminal(), "user2");

        CliTool.ExitStatus status = execute(cmd, settings);
        assertThat(status, is(CliTool.ExitStatus.NO_USER));

        assertThat(Files.exists(userFile), is(false));
        assertThat(Files.exists(userRolesFile), is(false));
    }

    @Test
    public void testPasswd_Parse_AllOptions() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("passwd", args("user1 -p changeme"));
        assertThat(command, instanceOf(ESUsersTool.Passwd.class));
        ESUsersTool.Passwd cmd = (ESUsersTool.Passwd) command;
        assertThat(cmd.username, equalTo("user1"));
        assertThat(new String(cmd.passwd.internalChars()), equalTo("changeme"));
    }

    @Test
    public void testPasswd_Parse_MissingUsername() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("passwd", args("-p changeme"));
        assertThat(command, instanceOf(ESUsersTool.Command.Exit.class));
        ESUsersTool.Command.Exit cmd = (ESUsersTool.Command.Exit) command;
        assertThat(cmd.status(), is(CliTool.ExitStatus.USAGE));
    }

    @Test
    public void testPasswd_Parse_ExtraArgs() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("passwd", args("user1 user2 -p changeme"));
        assertThat(command, instanceOf(ESUsersTool.Command.Exit.class));
        ESUsersTool.Command.Exit cmd = (ESUsersTool.Command.Exit) command;
        assertThat(cmd.status(), is(CliTool.ExitStatus.USAGE));
    }

    @Test
    public void testPasswd_Parse_MissingPassword() throws Exception {
        final AtomicReference<Boolean> secretRequested = new AtomicReference<>(false);
        Terminal terminal = new MockTerminal() {
            @Override
            public char[] readSecret(String text, Object... args) {
                secretRequested.set(true);
                return "changeme".toCharArray();
            }
        };
        ESUsersTool tool = new ESUsersTool(terminal);
        CliTool.Command command = tool.parse("passwd", args("user1"));
        assertThat(command, instanceOf(ESUsersTool.Passwd.class));
        ESUsersTool.Passwd cmd = (ESUsersTool.Passwd) command;
        assertThat(cmd.username, equalTo("user1"));
        assertThat(new String(cmd.passwd.internalChars()), equalTo("changeme"));
        assertThat(secretRequested.get(), is(true));
    }

    @Test
    public void testPasswd_Cmd() throws Exception {
        Path userFile = writeFile("user1:hash2");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", userFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Passwd cmd = new ESUsersTool.Passwd(new MockTerminal(), "user1", "changeme".toCharArray());
        CliTool.ExitStatus status = execute(cmd, settings);
        assertThat(status, is(CliTool.ExitStatus.OK));

        List<String> lines = Files.readAllLines(userFile, Charsets.UTF_8);
        assertThat(lines.size(), is(1));
        // we can't just hash again and compare the lines, as every time we hash a new salt is generated
        // instead we'll just verify the generated hash against the correct password.
        String line = lines.get(0);
        assertThat(line, startsWith("user1:"));
        String hash = line.substring("user1:".length());
        assertThat(Hasher.BCRYPT.verify(SecuredStringTests.build("changeme"), hash.toCharArray()), is(true));
    }

    @Test
    public void testPasswd_Cmd_UnknownUser() throws Exception {
        Path userFile = writeFile("user1:hash2");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", userFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Passwd cmd = new ESUsersTool.Passwd(new MockTerminal(), "user2", "changeme".toCharArray());
        CliTool.ExitStatus status = execute(cmd, settings);
        assertThat(status, is(CliTool.ExitStatus.NO_USER));
    }

    @Test
    public void testPasswd_Cmd_MissingFiles() throws Exception {
        Path userFile = createTempFile();
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", userFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Passwd cmd = new ESUsersTool.Passwd(new MockTerminal(), "user2", "changeme".toCharArray());
        CliTool.ExitStatus status = execute(cmd, settings);
        assertThat(status, is(CliTool.ExitStatus.NO_USER));
    }

    @Test
    public void testRoles_Parse_AllOptions() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("roles", args("someuser -a test1,test2,test3 -r test4,test5,test6"));
        assertThat(command, instanceOf(ESUsersTool.Roles.class));
        ESUsersTool.Roles rolesCommand = (ESUsersTool.Roles) command;
        assertThat(rolesCommand.username, is("someuser"));
        assertThat(rolesCommand.addRoles, arrayContaining("test1", "test2", "test3"));
        assertThat(rolesCommand.removeRoles, arrayContaining("test4", "test5", "test6"));
    }

    @Test
    public void testRoles_Parse_ExtraArgs() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("roles", args("someuser -a test1,test2,test3 foo -r test4,test5,test6 bar"));
        assertThat(command, instanceOf(ESUsersTool.Command.Exit.class));
        ESUsersTool.Command.Exit cmd = (ESUsersTool.Command.Exit) command;
        assertThat(cmd.status(), is(CliTool.ExitStatus.USAGE));
    }

    @Test
    public void testRoles_Cmd_validatingRoleNames() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        Path usersFile = writeFile("admin:hash");
        Path usersRoleFile = writeFile("admin: admin\n");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("path.home", createTempDir())
                .build();

        // invalid role names
        assertThat(execute(tool.parse("roles", args("admin -a r0le!")), settings), is(CliTool.ExitStatus.DATA_ERROR));
        assertThat(execute(tool.parse("roles", args("admin -a role%")), settings), is(CliTool.ExitStatus.DATA_ERROR));
        assertThat(execute(tool.parse("roles", args("admin -a role:")), settings), is(CliTool.ExitStatus.DATA_ERROR));
        assertThat(execute(tool.parse("roles", args("admin -a role>")), settings), is(CliTool.ExitStatus.DATA_ERROR));

        // valid role names
        assertThat(execute(tool.parse("roles", args("admin -a test01")), settings), is(CliTool.ExitStatus.OK));
        assertThat(execute(tool.parse("roles", args("admin -a @role")), settings), is(CliTool.ExitStatus.OK));
        assertThat(execute(tool.parse("roles", args("admin -a _role")), settings), is(CliTool.ExitStatus.OK));
        assertThat(execute(tool.parse("roles", args("admin -a -role")), settings), is(CliTool.ExitStatus.OK));
        assertThat(execute(tool.parse("roles", args("admin -a -Role")), settings), is(CliTool.ExitStatus.OK));
        assertThat(execute(tool.parse("roles", args("admin -a role0")), settings), is(CliTool.ExitStatus.OK));
    }

    @Test
    public void testRoles_Cmd_addingRoleWorks() throws Exception {
        Path usersFile = writeFile("admin:hash\nuser:hash");
        Path usersRoleFile = writeFile("admin: admin\nuser: user\n");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Roles cmd = new ESUsersTool.Roles(new MockTerminal(), "user", new String[]{"foo"}, Strings.EMPTY_ARRAY);
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));

        Map<String, String[]> userRoles = FileUserRolesStore.parseFile(usersRoleFile, logger);
        assertThat(userRoles.keySet(), hasSize(2));
        assertThat(userRoles.keySet(), hasItems("admin", "user"));
        assertThat(userRoles.get("admin"), arrayContaining("admin"));
        assertThat(userRoles.get("user"), arrayContainingInAnyOrder("user", "foo"));
    }

    @Test
    public void testRoles_Cmd_removingRoleWorks() throws Exception {
        Path usersFile = writeFile("admin:hash\nuser:hash");
        Path usersRoleFile = writeFile("admin: admin\nuser: user\nfoo: user\nbar: user\n");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Roles cmd = new ESUsersTool.Roles(new MockTerminal(), "user", Strings.EMPTY_ARRAY, new String[]{"foo"});
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));

        Map<String, String[]> userRoles = FileUserRolesStore.parseFile(usersRoleFile, logger);
        assertThat(userRoles.keySet(), hasSize(2));
        assertThat(userRoles.keySet(), hasItems("admin", "user"));
        assertThat(userRoles.get("admin"), arrayContaining("admin"));
        assertThat(userRoles.get("user"), arrayContainingInAnyOrder("user", "bar"));
    }

    @Test
    public void testRoles_Cmd_addingAndRemovingRoleWorks() throws Exception {
        Path usersFile = writeFile("admin:hash\nuser:hash");
        Path usersRoleFile = writeFile("admin: admin\nuser:user\nfoo:user\nbar:user\n");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Roles cmd = new ESUsersTool.Roles(new MockTerminal(), "user", new String[]{"newrole"}, new String[]{"foo"});
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));

        Map<String, String[]> userRoles = FileUserRolesStore.parseFile(usersRoleFile, logger);
        assertThat(userRoles.keySet(), hasSize(2));
        assertThat(userRoles.keySet(), hasItems("admin", "user"));
        assertThat(userRoles.get("admin"), arrayContaining("admin"));
        assertThat(userRoles.get("user"), arrayContainingInAnyOrder("user", "bar", "newrole"));
    }

    @Test
    public void testRoles_Cmd_removingLastRoleRemovesEntryFromRolesFile() throws Exception {
        Path usersFile = writeFile("admin:hash\nuser:hash");
        Path usersRoleFile = writeFile("admin: admin\nuser:user\nfoo:user\nbar:user\n");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Roles cmd = new ESUsersTool.Roles(new MockTerminal(), "user", Strings.EMPTY_ARRAY, new String[]{"user", "foo", "bar"});
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));

        List<String> usersRoleFileLines = Files.readAllLines(usersRoleFile, Charsets.UTF_8);
        assertThat(usersRoleFileLines, not(hasItem(containsString("user"))));
    }

    @Test
    public void testRoles_Cmd_userNotFound() throws Exception {
        Path usersFile = writeFile("admin:hash\nuser:hash");
        Path usersRoleFile = writeFile("admin: admin\nuser: user\nfoo:user\nbar:user\n");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("path.home", createTempDir())
                .build();

        ESUsersTool.Roles cmd = new ESUsersTool.Roles(new MockTerminal(), "does-not-exist", Strings.EMPTY_ARRAY, Strings.EMPTY_ARRAY);
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.NO_USER));
    }

    @Test
    public void testRoles_Cmd_testNotAddingOrRemovingRolesShowsListingOfRoles() throws Exception {
        Path usersFile = writeFile("admin:hash\nuser:hash");
        Path usersRoleFile = writeFile("admin: admin\nuser:user\nfoo:user\nbar:user\n");
        Path rolesFile = writeFile("admin:\n  cluster: all\n\nuser:\n  cluster: all\n\nfoo:\n  cluster: all\n\nbar:\n  cluster: all");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("shield.authz.store.files.roles", rolesFile)
                .put("path.home", createTempDir())
                .build();

        CaptureOutputTerminal catchTerminalOutput = new CaptureOutputTerminal();
        ESUsersTool.Roles cmd = new ESUsersTool.Roles(catchTerminalOutput, "user", Strings.EMPTY_ARRAY, Strings.EMPTY_ARRAY);
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(allOf(containsString("user"), containsString("user,foo,bar"))));
    }

    @Test
    public void testRoles_cmd_testRoleCanBeAddedWhenUserIsNotInRolesFile() throws Exception {
        Path usersFile = writeFile("admin:hash\nuser:hash");
        Path usersRoleFile = writeFile("admin: admin\n");
        Path rolesFile = writeFile("admin:\n  cluster: all\n\nmyrole:\n  cluster: all");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("shield.authz.store.files.roles", rolesFile)
                .put("path.home", createTempDir())
                .build();

        CaptureOutputTerminal catchTerminalOutput = new CaptureOutputTerminal();
        ESUsersTool.Roles cmd = new ESUsersTool.Roles(catchTerminalOutput, "user", new String[]{"myrole"}, Strings.EMPTY_ARRAY);
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));
        Map<String, String[]> userRoles = FileUserRolesStore.parseFile(usersRoleFile, logger);
        assertThat(userRoles.keySet(), hasSize(2));
        assertThat(userRoles.keySet(), hasItems("admin", "user"));
        assertThat(userRoles.get("user"), arrayContaining("myrole"));
    }

    @Test
    public void testListUsersAndRoles_Cmd_parsingWorks() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("list", args("someuser"));
        assertThat(command, instanceOf(ESUsersTool.ListUsersAndRoles.class));
        ESUsersTool.ListUsersAndRoles listUsersAndRolesCommand = (ESUsersTool.ListUsersAndRoles) command;
        assertThat(listUsersAndRolesCommand.username, is("someuser"));
    }

    @Test
    public void testListUsersAndRoles_Cmd_parsingExtraArgs() throws Exception {
        ESUsersTool tool = new ESUsersTool();
        CliTool.Command command = tool.parse("list", args("someuser two"));
        assertThat(command, instanceOf(ESUsersTool.Command.Exit.class));
        ESUsersTool.Command.Exit cmd = (ESUsersTool.Command.Exit) command;
        assertThat(cmd.status(), is(CliTool.ExitStatus.USAGE));
    }

    @Test
    public void testListUsersAndRoles_Cmd_listAllUsers() throws Exception {
        Path usersRoleFile = writeFile("admin: admin\nuser: user\nfoo:user\nbar:user\n");
        Path rolesFile = writeFile("admin:\n  cluster: all\n\nuser:\n  cluster: all\n\nfoo:\n  cluster: all\n\nbar:\n  cluster: all");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("shield.authz.store.files.roles", rolesFile)
                .put("path.home", createTempDir())
                .build();

        CaptureOutputTerminal catchTerminalOutput = new CaptureOutputTerminal();
        ESUsersTool.ListUsersAndRoles cmd = new ESUsersTool.ListUsersAndRoles(catchTerminalOutput, null);
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasSize(greaterThanOrEqualTo(2)));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(containsString("admin")));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(allOf(containsString("user"), containsString("user,foo,bar"))));
    }

    @Test
    public void testListUsersAndRoles_Cmd_listAllUsers_WithUnknownRoles() throws Exception {
        Path usersRoleFile = writeFile("admin: admin\nuser: user\nfoo:user\nbar:user\n");
        Path rolesFile = writeFile("admin:\n  cluster: all\n\nuser:\n  cluster: all");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("shield.authz.store.files.roles", rolesFile)
                .put("path.home", createTempDir())
                .build();

        CaptureOutputTerminal catchTerminalOutput = new CaptureOutputTerminal();
        ESUsersTool.ListUsersAndRoles cmd = new ESUsersTool.ListUsersAndRoles(catchTerminalOutput, null);
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasSize(greaterThanOrEqualTo(2)));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(containsString("admin")));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(allOf(containsString("user"), containsString("user,foo*,bar*"))));
    }

    @Test
    public void testListUsersAndRoles_Cmd_listSingleUser() throws Exception {
        Path usersRoleFile = writeFile("admin: admin\nuser: user\nfoo:user\nbar:user\n");
        Path usersFile = writeFile("admin:{plain}changeme\nuser:{plain}changeme\nno-roles-user:{plain}changeme\n");
        Path rolesFile = writeFile("admin:\n  cluster: all\n\nuser:\n  cluster: all\n\nfoo:\n  cluster: all");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authz.store.files.roles", rolesFile)
                .put("path.home", createTempDir())
                .build();

        CaptureOutputTerminal catchTerminalOutput = new CaptureOutputTerminal();
        ESUsersTool.ListUsersAndRoles cmd = new ESUsersTool.ListUsersAndRoles(catchTerminalOutput, "admin");
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasSize(greaterThanOrEqualTo(1)));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(containsString("admin")));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(not(containsString("user"))));
    }

    @Test
    public void testListUsersAndRoles_Cmd_NoUsers() throws Exception {
        Path usersFile = writeFile("");
        Path usersRoleFile = writeFile("");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("path.home", createTempDir())
                .build();

        CaptureOutputTerminal terminal = new CaptureOutputTerminal();
        ESUsersTool.ListUsersAndRoles cmd = new ESUsersTool.ListUsersAndRoles(terminal, null);
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));
        List<String> output = terminal.getTerminalOutput();
        assertThat(output, hasSize(1));
        assertThat(output.get(0), equalTo("No users found" + System.lineSeparator()));
    }

    @Test
    public void testListUsersAndRoles_Cmd_listSingleUserNotFound() throws Exception {
        Path usersRoleFile = writeFile("admin: admin\nuser: user\nfoo:user\nbar:user\n");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("path.home", createTempDir())
                .build();

        CaptureOutputTerminal catchTerminalOutput = new CaptureOutputTerminal();
        ESUsersTool.ListUsersAndRoles cmd = new ESUsersTool.ListUsersAndRoles(catchTerminalOutput, "does-not-exist");
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.NO_USER));
    }

    @Test
    public void testListUsersAndRoles_Cmd_testThatUsersWithAndWithoutRolesAreListed() throws Exception {
        Path usersFile = writeFile("admin:{plain}changeme\nuser:{plain}changeme\nno-roles-user:{plain}changeme\n");
        Path usersRoleFile = writeFile("admin: admin\nuser: user\nfoo:user\nbar:user\n");
        Path rolesFile = writeFile("admin:\n  cluster: all\n\nuser:\n  cluster: all\n\nfoo:\n  cluster: all\n\nbar:\n  cluster: all");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("shield.authz.store.files.roles", rolesFile)
                .put("path.home", createTempDir())
                .build();

        CaptureOutputTerminal catchTerminalOutput = new CaptureOutputTerminal();
        ESUsersTool.ListUsersAndRoles cmd = new ESUsersTool.ListUsersAndRoles(catchTerminalOutput, null);
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasSize(greaterThanOrEqualTo(3)));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(containsString("admin")));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(allOf(containsString("user"), containsString("user,foo,bar"))));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(allOf(containsString("no-roles-user"), containsString("-"))));
    }

    @Test
    public void testListUsersAndRoles_Cmd_testThatUsersWithoutRolesAreListed() throws Exception {
        Path usersFile = writeFile("admin:{plain}changeme\nuser:{plain}changeme\nno-roles-user:{plain}changeme\n");
        Path usersRoleFile = writeFile("");
        Path rolesFile = writeFile("admin:\n  cluster: all\n\nuser:\n  cluster: all\n\nfoo:\n  cluster: all\n\nbar:\n  cluster: all");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("shield.authz.store.files.roles", rolesFile)
                .put("path.home", createTempDir())
                .build();

        CaptureOutputTerminal catchTerminalOutput = new CaptureOutputTerminal();
        ESUsersTool.ListUsersAndRoles cmd = new ESUsersTool.ListUsersAndRoles(catchTerminalOutput, null);
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasSize(greaterThanOrEqualTo(3)));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(allOf(containsString("admin"), containsString("-"))));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(allOf(containsString("user"), containsString("-"))));
        assertThat(catchTerminalOutput.getTerminalOutput(), hasItem(allOf(containsString("no-roles-user"), containsString("-"))));
    }

    @Test
    public void testListUsersAndRoles_Cmd_testThatUsersWithoutRolesAreListedForSingleUser() throws Exception {
        Path usersFile = writeFile("admin:{plain}changeme");
        Path usersRoleFile = writeFile("");
        Settings settings = Settings.builder()
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.files.users_roles", usersRoleFile)
                .put("shield.authc.realms.esusers.files.users", usersFile)
                .put("path.home", createTempDir())
                .build();

        CaptureOutputTerminal loggingTerminal = new CaptureOutputTerminal();
        ESUsersTool.ListUsersAndRoles cmd = new ESUsersTool.ListUsersAndRoles(loggingTerminal, "admin");
        CliTool.ExitStatus status = execute(cmd, settings);

        assertThat(status, is(CliTool.ExitStatus.OK));
        assertThat(loggingTerminal.getTerminalOutput(), hasSize(greaterThanOrEqualTo(1)));
        assertThat(loggingTerminal.getTerminalOutput(), hasItem(allOf(containsString("admin"), containsString("-"))));
    }

    private CliTool.ExitStatus execute(CliTool.Command cmd, Settings settings) throws Exception {
        Environment env = new Environment(settings);
        return cmd.execute(settings, env);
    }

    private Path writeFile(String content) throws IOException {
        Path file = createTempFile();
        Files.write(file, content.getBytes(Charsets.UTF_8));
        return file;
    }

    private void assertFileExists(Path file) {
        assertThat(String.format(Locale.ROOT, "Expected file [%s] to exist", file), Files.exists(file), is(true));
    }
}
