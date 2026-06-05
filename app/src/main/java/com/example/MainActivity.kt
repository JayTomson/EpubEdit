package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.AppDatabase
import com.example.data.BookRepository
import com.example.ui.screens.DetailsScreen
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.BookViewModel
import com.example.viewmodel.BookViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Persistence Layers
        val database = AppDatabase.getDatabase(this)
        val repository = BookRepository(database.bookDao())
        
        // Setup ViewModel Custom Factory
        val viewModelFactory = BookViewModelFactory(repository)
        val viewModel = ViewModelProvider(this, viewModelFactory)[BookViewModel::class.java]

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = "library",
                    modifier = androidx.compose.ui.Modifier.fillMaxSize()
                ) {
                    // 1. Library Titles Grid List
                    composable("library") {
                        LibraryScreen(
                            viewModel = viewModel,
                            onBookClick = { titleId ->
                                navController.navigate("details/$titleId")
                            }
                        )
                    }

                    // 2. Book Subsections/Tabs Detail Screen
                    composable("details/{titleId}") { backStackEntry ->
                        val titleId = backStackEntry.arguments?.getString("titleId")?.toLongOrNull() ?: 0L
                        DetailsScreen(
                            viewModel = viewModel,
                            titleId = titleId,
                            onBackClick = {
                                navController.popBackStack()
                            },
                            onChapterEditClick = { chapterId ->
                                navController.navigate("editor/$chapterId")
                            }
                        )
                    }

                    // 3. Visual & HTML chapter rich editor
                    composable("editor/{chapterId}") { backStackEntry ->
                        val chapterId = backStackEntry.arguments?.getString("chapterId")?.toLongOrNull() ?: 0L
                        EditorScreen(
                            viewModel = viewModel,
                            chapterId = chapterId,
                            onBackClick = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}
