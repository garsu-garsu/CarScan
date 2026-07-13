package com.bruni.carscan

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

@Suppress("unused", "FunctionName") // Called from Swift (ContentView.swift).
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
