from __future__ import annotations

import argparse
import sys
from pathlib import Path

import paramiko


DEFAULT_HOST = "47.104.147.148"
DEFAULT_USER = "root"
DEFAULT_KEY_PATH = Path(__file__).with_name("miyao.pem")
DEFAULT_DB = "sensor_monitor"
DEFAULT_TABLE = "iot_measurements"
DEFAULT_PASSWORD = "21030030689"


def run_remote_command(client: paramiko.SSHClient, command: str, label: str) -> tuple[int, str, str]:
    stdin, stdout, stderr = client.exec_command(command)
    exit_code = stdout.channel.recv_exit_status()
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    print(f"[{label}] exit_code={exit_code}")
    if out.strip():
        print(f"[{label}] stdout:\n{out.strip()}")
    if err.strip():
        print(f"[{label}] stderr:\n{err.strip()}")
    return exit_code, out, err


def build_mysql_sql(database: str, table: str, password: str) -> str:
    return f"""
CREATE DATABASE IF NOT EXISTS `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'mzq'@'%' IDENTIFIED BY '{password}';
ALTER USER 'mzq'@'%' IDENTIFIED BY '{password}';
GRANT ALL PRIVILEGES ON `{database}`.* TO 'mzq'@'%';
FLUSH PRIVILEGES;
USE `{database}`;
CREATE TABLE IF NOT EXISTS `{table}` (
    id INT AUTO_INCREMENT PRIMARY KEY,
    record_time DATETIME NOT NULL,
    device_id VARCHAR(50) NOT NULL,
    device_name VARCHAR(100),
    path_id VARCHAR(64) NOT NULL DEFAULT '',
    latitude DOUBLE,
    longitude DOUBLE,
    altitude DOUBLE,
    accel_x DOUBLE,
    accel_y DOUBLE,
    accel_z DOUBLE,
    gyro_x DOUBLE,
    gyro_y DOUBLE,
    gyro_z DOUBLE,
    pitch DOUBLE,
    roll DOUBLE,
    yaw DOUBLE,
    speed DOUBLE,
    mag_x DOUBLE,
    mag_y DOUBLE,
    mag_z DOUBLE,
    pressure DOUBLE,
    height DOUBLE,
    quat_w DOUBLE,
    quat_x DOUBLE,
    quat_y DOUBLE,
    quat_z DOUBLE,
    sv_count INT,
    pdop DOUBLE,
    hdop DOUBLE,
    vdop DOUBLE,
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
""".strip()


def build_remote_mysql_script(database: str, table: str, password: str) -> str:
    mysql_sql = build_mysql_sql(database, table, password)
    return (
        "python3 - <<'PY'\n"
        "import os\n"
        "import pymysql\n\n"
        f"database = {database!r}\n"
        f"table = {table!r}\n"
        f"password = {password!r}\n"
        f"sql = {mysql_sql!r}\n\n"
        "socket_candidates = [\n"
        "    '/var/run/mysqld/mysqld.sock',\n"
        "    '/run/mysqld/mysqld.sock',\n"
        "    '/var/lib/mysql/mysql.sock',\n"
        "]\n\n"
        "connection = None\n"
        "last_error = None\n"
        "for socket_path in socket_candidates:\n"
        "    if os.path.exists(socket_path):\n"
        "        try:\n"
        "            connection = pymysql.connect(\n"
        "                unix_socket=socket_path,\n"
        "                user='root',\n"
        "                charset='utf8mb4',\n"
        "                autocommit=True,\n"
        "            )\n"
        "            print(f'Connected via Unix socket: {socket_path}')\n"
        "            break\n"
        "        except Exception as exc:\n"
        "            last_error = exc\n\n"
        "if connection is None:\n"
        "    try:\n"
        "        connection = pymysql.connect(\n"
        "            host='127.0.0.1',\n"
        "            user='root',\n"
        "            charset='utf8mb4',\n"
        "            autocommit=True,\n"
        "        )\n"
        "        print('Connected via TCP: 127.0.0.1')\n"
        "    except Exception as exc:\n"
        "        raise SystemExit(f'Unable to connect to MySQL: {exc}; last_socket_error={last_error}')\n\n"
        "with connection.cursor() as cursor:\n"
        "    statements = [statement.strip() for statement in sql.split(';') if statement.strip()]\n"
        "    for statement in statements:\n"
        "        cursor.execute(statement)\n"
        "        print(f'Executed: {statement.splitlines()[0][:80]}')\n\n"
        "def column_exists(cursor, database, table, column):\n"
        "    cursor.execute(\n"
        "        \"SELECT 1 FROM information_schema.columns WHERE table_schema = %s AND table_name = %s AND column_name = %s LIMIT 1\",\n"
        "        (database, table, column),\n"
        "    )\n"
        "    return cursor.fetchone() is not None\n\n"
        "def index_exists(cursor, database, table, index_name):\n"
        "    cursor.execute(\n"
        "        \"SELECT 1 FROM information_schema.statistics WHERE table_schema = %s AND table_name = %s AND index_name = %s LIMIT 1\",\n"
        "        (database, table, index_name),\n"
        "    )\n"
        "    return cursor.fetchone() is not None\n\n"
        "with connection.cursor() as cursor:\n"
        "    column_defs = [\n"
        "        ('path_id', \"ALTER TABLE `{table}` ADD COLUMN `path_id` VARCHAR(64) NOT NULL DEFAULT '' AFTER `device_name`\"),\n"
        "        ('latitude', \"ALTER TABLE `{table}` ADD COLUMN `latitude` DOUBLE NULL AFTER `path_id`\"),\n"
        "        ('longitude', \"ALTER TABLE `{table}` ADD COLUMN `longitude` DOUBLE NULL AFTER `latitude`\"),\n"
        "        ('altitude', \"ALTER TABLE `{table}` ADD COLUMN `altitude` DOUBLE NULL AFTER `longitude`\"),\n"
        "        ('accel_x', \"ALTER TABLE `{table}` ADD COLUMN `accel_x` DOUBLE NULL AFTER `altitude`\"),\n"
        "        ('accel_y', \"ALTER TABLE `{table}` ADD COLUMN `accel_y` DOUBLE NULL AFTER `accel_x`\"),\n"
        "        ('accel_z', \"ALTER TABLE `{table}` ADD COLUMN `accel_z` DOUBLE NULL AFTER `accel_y`\"),\n"
        "        ('gyro_x', \"ALTER TABLE `{table}` ADD COLUMN `gyro_x` DOUBLE NULL AFTER `accel_z`\"),\n"
        "        ('gyro_y', \"ALTER TABLE `{table}` ADD COLUMN `gyro_y` DOUBLE NULL AFTER `gyro_x`\"),\n"
        "        ('gyro_z', \"ALTER TABLE `{table}` ADD COLUMN `gyro_z` DOUBLE NULL AFTER `gyro_y`\"),\n"
        "        ('pitch', \"ALTER TABLE `{table}` ADD COLUMN `pitch` DOUBLE NULL AFTER `gyro_z`\"),\n"
        "        ('roll', \"ALTER TABLE `{table}` ADD COLUMN `roll` DOUBLE NULL AFTER `pitch`\"),\n"
        "        ('yaw', \"ALTER TABLE `{table}` ADD COLUMN `yaw` DOUBLE NULL AFTER `roll`\"),\n"
        "        ('speed', \"ALTER TABLE `{table}` ADD COLUMN `speed` DOUBLE NULL AFTER `yaw`\"),\n"
        "        ('mag_x', \"ALTER TABLE `{table}` ADD COLUMN `mag_x` DOUBLE NULL AFTER `speed`\"),\n"
        "        ('mag_y', \"ALTER TABLE `{table}` ADD COLUMN `mag_y` DOUBLE NULL AFTER `mag_x`\"),\n"
        "        ('mag_z', \"ALTER TABLE `{table}` ADD COLUMN `mag_z` DOUBLE NULL AFTER `mag_y`\"),\n"
        "        ('pressure', \"ALTER TABLE `{table}` ADD COLUMN `pressure` DOUBLE NULL AFTER `mag_z`\"),\n"
        "        ('height', \"ALTER TABLE `{table}` ADD COLUMN `height` DOUBLE NULL AFTER `pressure`\"),\n"
        "        ('quat_w', \"ALTER TABLE `{table}` ADD COLUMN `quat_w` DOUBLE NULL AFTER `height`\"),\n"
        "        ('quat_x', \"ALTER TABLE `{table}` ADD COLUMN `quat_x` DOUBLE NULL AFTER `quat_w`\"),\n"
        "        ('quat_y', \"ALTER TABLE `{table}` ADD COLUMN `quat_y` DOUBLE NULL AFTER `quat_x`\"),\n"
        "        ('quat_z', \"ALTER TABLE `{table}` ADD COLUMN `quat_z` DOUBLE NULL AFTER `quat_y`\"),\n"
        "        ('sv_count', \"ALTER TABLE `{table}` ADD COLUMN `sv_count` INT NULL AFTER `quat_z`\"),\n"
        "        ('pdop', \"ALTER TABLE `{table}` ADD COLUMN `pdop` DOUBLE NULL AFTER `sv_count`\"),\n"
        "        ('hdop', \"ALTER TABLE `{table}` ADD COLUMN `hdop` DOUBLE NULL AFTER `pdop`\"),\n"
        "        ('vdop', \"ALTER TABLE `{table}` ADD COLUMN `vdop` DOUBLE NULL AFTER `hdop`\"),\n"
        "    ]\n"
        "    for column_name, statement_template in column_defs:\n"
        "        if not column_exists(cursor, database, table, column_name):\n"
        "            cursor.execute(statement_template.format(table=table))\n"
        "            print(f'Added column: {column_name}')\n"
        "    index_defs = [\n"
        "        ('idx_iot_measurements_path_id', 'path_id'),\n"
        "        ('idx_iot_measurements_record_time', 'record_time'),\n"
        "    ]\n"
        "    for index_name, column_name in index_defs:\n"
        "        if not index_exists(cursor, database, table, index_name):\n"
        "            cursor.execute(f'CREATE INDEX `{index_name}` ON `{table}` (`{column_name}`)')\n"
        "            print(f'Created index: {index_name}')\n\n"
        "with connection.cursor() as cursor:\n"
        "    cursor.execute(\"SHOW DATABASES LIKE %s\", (database,))\n"
        "    print('Database check:', cursor.fetchone())\n"
        "    cursor.execute(\"SHOW TABLES LIKE %s\", (table,))\n"
        "    print('Table check:', cursor.fetchone())\n"
        "    cursor.execute(\"SELECT user, host FROM mysql.user WHERE user IN ('root', 'mzq') ORDER BY user, host\")\n"
        "    for row in cursor.fetchall():\n"
        "        print('User row:', row)\n\n"
        "connection.close()\n"
        "print('MySQL initialization complete.')\n"
        "PY"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Initialize MySQL over SSH using Paramiko.")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--user", default=DEFAULT_USER)
    parser.add_argument("--key", default=str(DEFAULT_KEY_PATH))
    parser.add_argument("--database", default=DEFAULT_DB)
    parser.add_argument("--table", default=DEFAULT_TABLE)
    parser.add_argument("--password", default=DEFAULT_PASSWORD)
    args = parser.parse_args()

    key_path = Path(args.key)
    if not key_path.exists():
        print(f"SSH key not found: {key_path}", file=sys.stderr)
        return 2

    key = paramiko.RSAKey.from_private_key_file(str(key_path))
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())

    try:
        print(f"Connecting to {args.user}@{args.host} with key {key_path}...")
        client.connect(
            hostname=args.host,
            username=args.user,
            pkey=key,
            allow_agent=False,
            look_for_keys=False,
            timeout=15,
            banner_timeout=15,
            auth_timeout=15,
        )
        print("SSH connection established.")

        bootstrap_probe = (
            "python3 - <<'PY'\n"
            "try:\n"
            "    import pymysql\n"
            "    print('pymysql-present')\n"
            "except Exception as exc:\n"
            "    raise SystemExit(f'pymysql-missing: {exc}')\n"
            "PY"
        )
        exit_code, _, err = run_remote_command(client, bootstrap_probe, "remote-pymysql-check")
        if exit_code != 0:
            install_command = (
                "export DEBIAN_FRONTEND=noninteractive; "
                "apt-get update && apt-get install -y mariadb-server python3-pymysql"
            )
            run_remote_command(client, install_command, "install-mariadb-and-pymysql")

        start_command = (
            "systemctl enable --now mariadb 2>/dev/null || "
            "systemctl enable --now mysql 2>/dev/null || "
            "service mariadb start || service mysql start"
        )
        run_remote_command(client, start_command, "start-mysql-service")

        mysql_init_command = build_remote_mysql_script(args.database, args.table, args.password)
        exit_code, _, _ = run_remote_command(client, mysql_init_command, "mysql-init/pymysql")

        firewall_checks = [
            (
                "ufw-status",
                "if command -v ufw >/dev/null 2>&1; then ufw status verbose; else echo 'ufw not installed'; fi",
            ),
            (
                "firewalld-status",
                "if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --list-ports; else echo 'firewall-cmd not installed'; fi",
            ),
        ]
        for label, command in firewall_checks:
            run_remote_command(client, command, label)

        print("Final status: completed SSH session and executed initialization commands.")
        return 0 if exit_code == 0 else exit_code
    except Exception as exc:
        print(f"Execution failed: {exc}", file=sys.stderr)
        return 1
    finally:
        client.close()


if __name__ == "__main__":
    raise SystemExit(main())