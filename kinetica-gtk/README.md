# Kinetica GTK renderer

The renderer targets Linux with GTK4 development headers. Generate the local cinterop
definition with `./kinetica-gtk/generate-def.sh` before building.

Text inputs mask password values and apply GTK input-purpose hints for password, email,
telephone and URL types. Changing type updates the existing entry; omitting it restores
visible free-form text.

Run native widget tests on Linux with a display, or under Xvfb:

```sh
xvfb-run -a ./kotlin test --platform linuxX64 -m kinetica-gtk
```

These tests cannot run on macOS; GTK cinterop needs the Linux headers and libraries.
