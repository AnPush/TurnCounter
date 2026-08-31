# TurnCounter

![Android CI](https://github.com/USERNAME/TurnCounter/actions/workflows/android.yml/badge.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blue)
![Min SDK](https://img.shields.io/badge/MinSDK-24-green)
![License](https://img.shields.io/badge/License-MIT-yellow)

**TurnCounter** is an Android application that counts wire turns or coil revolutions by detecting magnetic field changes with the built-in smartphone magnetometer.

A small magnet is attached to the rotating part of a coil, spool or winding mechanism. When the magnet passes near the smartphone, the app detects a magnetic field spike and increments the counter.

> This project is intended for prototyping, hobby winding machines and low/medium speed turn counting. For industrial or high-speed counting, an external Hall sensor, optical sensor or encoder is recommended.

---

## Features

- Counts magnetic field changes using the smartphone magnetometer
- Vector-based magnetic field change detection
- Real-time signal graph
- Adjustable sensitivity threshold
- Optional adaptive threshold
- Minimum debounce interval between events
- Configurable number of events per turn
- Sound and vibration feedback
- CSV event log export
- Settings and counter persistence
- GitHub Actions CI builds debug APK automatically

---

## How It Works

The app reads the magnetic field sensor values:

```text
x, y, z — magnetic field vector components
