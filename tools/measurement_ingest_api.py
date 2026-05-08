from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pymysql


HOST = "0.0.0.0"
PORT = 18080
DB_NAME = "sensor_monitor"
TABLE_NAME = "iot_measurements"


def get_connection() -> pymysql.connections.Connection:
    socket_candidates = [
        "/var/run/mysqld/mysqld.sock",
        "/run/mysqld/mysqld.sock",
        "/var/lib/mysql/mysql.sock",
    ]
    for socket_path in socket_candidates:
        if os.path.exists(socket_path):
            try:
                return pymysql.connect(
                    unix_socket=socket_path,
                    user="root",
                    charset="utf8mb4",
                    autocommit=True,
                )
            except Exception:
                continue
    return pymysql.connect(
        host="127.0.0.1",
        user="root",
        charset="utf8mb4",
        autocommit=True,
    )


class MeasurementHandler(BaseHTTPRequestHandler):
    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:
        if self.path != "/measurements":
            self._send_json(404, {"ok": False, "error": "not found"})
            return

        content_length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(content_length)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except Exception:
            self._send_json(400, {"ok": False, "error": "invalid json"})
            return

        required = ["record_time", "device_id", "device_name", "path_id"]
        for field in required:
            if not payload.get(field):
                self._send_json(400, {"ok": False, "error": f"missing field: {field}"})
                return

        sql = f"""
            INSERT INTO {TABLE_NAME} (
                record_time,
                device_id,
                device_name,
                path_id,
                latitude,
                longitude,
                altitude,
                accel_x,
                accel_y,
                accel_z,
                gyro_x,
                gyro_y,
                gyro_z,
                pitch,
                roll,
                yaw,
                speed
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        """

        try:
            conn = get_connection()
            with conn.cursor() as cursor:
                cursor.execute(f"CREATE DATABASE IF NOT EXISTS `{DB_NAME}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
                cursor.execute(f"USE `{DB_NAME}`")
                cursor.execute(sql, (
                    payload.get("record_time"),
                    payload.get("device_id"),
                    payload.get("device_name"),
                    payload.get("path_id"),
                    payload.get("latitude"),
                    payload.get("longitude"),
                    payload.get("altitude"),
                    payload.get("accel_x"),
                    payload.get("accel_y"),
                    payload.get("accel_z"),
                    payload.get("gyro_x"),
                    payload.get("gyro_y"),
                    payload.get("gyro_z"),
                    payload.get("pitch"),
                    payload.get("roll"),
                    payload.get("yaw"),
                    payload.get("speed"),
                ))
                inserted_id = cursor.lastrowid
            conn.close()
        except Exception as exc:
            self._send_json(500, {"ok": False, "error": str(exc)})
            return

        self._send_json(200, {"ok": True, "id": inserted_id})

    def do_GET(self) -> None:
        if self.path == "/health":
            self._send_json(200, {"ok": True})
            return
        self._send_json(404, {"ok": False, "error": "not found"})

    def log_message(self, format: str, *args) -> None:
        return


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), MeasurementHandler)
    server.serve_forever()