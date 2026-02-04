package com.tatilacratita.lgcast.sampler

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.graphics.toColorInt
import androidx.core.graphics.ColorUtils


class YouTubeSearchDialog(
    context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val initialQuery: String? = null,
    private val onVideoSelected: (String, String) -> Unit, // videoId, title
    private val onAutoplayRequested: (List<Pair<String, String>>) -> Unit,
    private val onVoiceSearchRequested: () -> Unit
) : Dialog(context) {

    private lateinit var searchInput: EditText
    private lateinit var resultsContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var noResultsText: TextView
    private lateinit var suggestionsContainer: LinearLayout // Added for suggestions

    private val innerTubeSearchHelper = InnerTubeSearchHelper()
    private var searchJob: Job? = null

    data class SearchSuggestion(val title: String, val videoIdForThumbnail: String) {
        val thumbnailUrl: String
            get() = "https://i.ytimg.com/vi/$videoIdForThumbnail/hqdefault.jpg"
    }

    private val suggestions = listOf(
        SearchSuggestion("Manele Noi 2025", "jQRxfoUCIV4"),
        SearchSuggestion("Muzica Lautareasca", "nHJzLOPbrIg"),
        SearchSuggestion("Hits 2025", "JGwWNGJdvx8"),
        SearchSuggestion("Hip Hop Romania", "ipQ2abE2JDc"),
        SearchSuggestion("Rock Clasic", "zXrvvRvguoQ"),
        SearchSuggestion("K-Pop", "UQDP7ftGWF8")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Main container with rounded corners
        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor("#121212".toColorInt()) // Dark background
        }

        // Header with title and close button
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 16)
        }

        // Title
        val titleText = TextView(context).apply {
            text = context.getString(R.string.search_youtube_title)
            textSize = 24f
            setTextColor(Color.WHITE) // White text on dark background
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerLayout.addView(titleText)

        // Close button
        val closeButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel) // Default white close icon is good for dark background
            setBackgroundResource(android.R.color.transparent)
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { dismiss() }
        }
        headerLayout.addView(closeButton)

        mainLayout.addView(headerLayout)

        // Search input container with rounded background
        val searchInputContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = createRoundedBackground("#FF0000".toColorInt(), 24f) // YouTube Red background
            setPadding(16, 8, 16, 8)
        }

        // Search input
        searchInput = EditText(context).apply {
            hint = context.getString(R.string.search_youtube_hint)
            setPadding(16, 16, 16, 16)
            textSize = 16f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE) // White text on red background
            setHintTextColor("#AAAAAA".toColorInt()) // Lighter hint text
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        searchInputContainer.addView(searchInput)

        // Microphone button
        val micButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_mic) // Assuming ic_mic is visible on red, or will be tinted.
            setBackgroundResource(android.R.color.transparent)
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener {
                onVoiceSearchRequested()
                dismiss()
            }
        }
        searchInputContainer.addView(micButton)

        mainLayout.addView(searchInputContainer)

        // Suggestions container
        suggestionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 24, 0, 0) // Margin above suggestions
            }
            visibility = android.view.View.GONE // Initially hidden
        }
        mainLayout.addView(suggestionsContainer)

        // Progress bar
        progressBar = ProgressBar(context).apply {
            visibility = android.view.View.GONE
            setPadding(0, 24, 0, 24)
        }
        mainLayout.addView(progressBar)

        // No results text
        noResultsText = TextView(context).apply {
            text = context.getString(R.string.no_results_found)
            visibility = android.view.View.GONE
            setPadding(0, 24, 0, 24)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            textSize = 16f
            setTextColor("#E0E0E0".toColorInt()) // Light gray text
        }
        mainLayout.addView(noResultsText)

        // Results container in ScrollView
        resultsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f // Take remaining space
            )
            addView(resultsContainer)
        }
        mainLayout.addView(scrollView)

        setContentView(mainLayout)

        // Set dialog to fullscreen
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        setupSearchListener()

        // If an initial query is provided, set it and search
        initialQuery?.let {
            searchInput.setText(it)
            searchInput.setSelection(it.length)
            if (it.length >= 3) {
                performSearch(it)
            } else {
                displaySuggestions(suggestions) // Display suggestions if query is too short
            }
        } ?: run {
            displaySuggestions(suggestions) // Display suggestions if no initial query
        }
    }

    private fun createRoundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun setupSearchListener() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""

                searchJob?.cancel()

                if (query.length >= 3) {
                    searchJob = lifecycleScope.launch {
                        delay(500)
                        performSearch(query)
                    }
                    suggestionsContainer.visibility = android.view.View.GONE // Hide suggestions
                } else {
                    resultsContainer.removeAllViews()
                    noResultsText.visibility = android.view.View.GONE
                    displaySuggestions(suggestions) // Show suggestions if query is too short
                }
            }
        })
    }

    private fun performSearch(query: String) {
        lifecycleScope.launch {
            try {
                progressBar.visibility = android.view.View.VISIBLE
                noResultsText.visibility = android.view.View.GONE
                resultsContainer.removeAllViews()
                suggestionsContainer.visibility = android.view.View.GONE // Hide suggestions when searching

                val innerTubeResults = innerTubeSearchHelper.searchVideos(query, maxResults = 15)

                val results = innerTubeResults.map {
                    YouTubeSearchHelper.SearchResult(
                        videoId = it.videoId,
                        title = it.title,
                        author = it.author,
                        thumbnail = it.thumbnail,
                        duration = it.duration,
                        views = it.views,
                        uploadedDate = it.uploadedDate
                    )
                }

                progressBar.visibility = android.view.View.GONE

                if (results.isEmpty()) {
                    noResultsText.text = context.getString(R.string.no_results_found)
                    noResultsText.visibility = android.view.View.VISIBLE
                } else {
                    displayResults(results)
                }
            } catch (_: Exception) {
                progressBar.visibility = android.view.View.GONE
                noResultsText.text = context.getString(R.string.search_error)
                noResultsText.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun displayResults(results: List<YouTubeSearchHelper.SearchResult>) {
        resultsContainer.removeAllViews()
        suggestionsContainer.visibility = android.view.View.GONE

        // ADAUGĂ BUTONUL DE AUTOPLAY LA ÎNCEPUT
        if (results.isNotEmpty()) {
            val autoplayButton = createAutoplayButton(results)
            resultsContainer.addView(autoplayButton)
        }

        for (result in results) {
            val itemView = createResultItem(result)
            resultsContainer.addView(itemView)
        }
    }

    // 3. Adaugă funcția pentru crearea butonului de autoplay:
    private fun createAutoplayButton(results: List<YouTubeSearchHelper.SearchResult>): LinearLayout {
        val buttonLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            background = createRoundedBackground("#FF0000".toColorInt(), 16f) // YouTube Red
            elevation = 4f
            setPadding(24, 16, 24, 16)
            gravity = android.view.Gravity.CENTER

            isClickable = true
            isFocusable = true

            setOnClickListener {
                val playlist = results.map { it.videoId to it.title }
                onAutoplayRequested(playlist)
                // Dialogul rămâne deschis pentru a vedea progresul
            }
        }

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_play) // Asigură-te că ai acest icon
            layoutParams = LinearLayout.LayoutParams(48, 48).apply {
                marginEnd = 16
            }
            setColorFilter(Color.WHITE)
        }
        buttonLayout.addView(icon)

        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val titleText = TextView(context).apply {
            text = "Redă tot (Autoplay)"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        textLayout.addView(titleText)

        val subtitleText = TextView(context).apply {
            text = "${results.size} videoclipuri"
            textSize = 14f
            setTextColor(ColorUtils.setAlphaComponent(Color.WHITE, (0.8f * 255).toInt()))
        }
        textLayout.addView(subtitleText)

        buttonLayout.addView(textLayout)

        return buttonLayout
    }

    private fun displaySuggestions(suggestions: List<SearchSuggestion>) {
        suggestionsContainer.removeAllViews()
        suggestionsContainer.visibility = android.view.View.VISIBLE // Show suggestions container

        val dp8 = (8 * context.resources.displayMetrics.density).toInt()
        val dp12 = (12 * context.resources.displayMetrics.density).toInt()

        var currentRow: LinearLayout? = null
        for ((index, suggestion) in suggestions.withIndex()) {
            if (index % 2 == 0) { // Start a new row for every two suggestions
                currentRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index > 0) topMargin = dp12 // Add vertical spacing between rows
                    }
                }
                suggestionsContainer.addView(currentRow)
            }

            val suggestionItem = createSuggestionItem(suggestion) { clickedSuggestion ->
                searchInput.setText(clickedSuggestion.title)
                searchInput.setSelection(clickedSuggestion.title.length)
                performSearch(clickedSuggestion.title)
            }

            currentRow?.addView(suggestionItem)
            (suggestionItem.layoutParams as LinearLayout.LayoutParams).apply {
                weight = 1f
                if (index % 2 == 0) { // Add right margin for the first item in a row
                    rightMargin = dp12
                }
            }
        }
    }

    private fun createSuggestionItem(suggestion: SearchSuggestion, onClick: (SearchSuggestion) -> Unit): LinearLayout {
        // Card container with rounded corners and shadow
        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, // Width will be set by weight in displaySuggestions
                (context.resources.displayMetrics.density * 180).toInt() // Fixed height for suggestion cards
            ).apply {
                // Margins are handled in displaySuggestions for grid-like spacing
            }
            background = createRoundedBackground("#1C1C1C".toColorInt(), 16f) // Darker card background
            elevation = 4f
            setPadding(12, 12, 12, 12)

            isClickable = true
            isFocusable = true

            setOnClickListener { onClick(suggestion) }
        }

        // Thumbnail Image
        val thumbnail = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, // Height will be set by weight to take most space
                1f
            ).apply {
                bottomMargin = (8 * context.resources.displayMetrics.density).toInt()
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true // Ensures rounded corners on image
            background = createRoundedBackground("#2C2C2C".toColorInt(), 12f) // Placeholder background for thumbnail
        }

        if (suggestion.thumbnailUrl.isNotEmpty()) {
            try {
                Picasso.get()
                    .load(suggestion.thumbnailUrl)
                    .placeholder(android.R.drawable.ic_menu_gallery) // Default gallery icon
                    .into(thumbnail)
            } catch (_: Exception) {
                thumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }
        cardLayout.addView(thumbnail)

        // Title
        val titleText = TextView(context).apply {
            text = suggestion.title
            textSize = 14f
            maxLines = 2
            setTextColor(Color.WHITE) // White text for title
            setLineSpacing(2f, 1f)
            // No bottom padding for now, let "Popular Category" be close
        }
        cardLayout.addView(titleText)

        // Category Text
        val categoryText = TextView(context).apply {
            text = "Categorie populară"
            textSize = 12f
            setTextColor(ColorUtils.setAlphaComponent(Color.WHITE, (0.8f * 255).toInt()))
        }
        cardLayout.addView(categoryText)

        return cardLayout
    }

    private fun createResultItem(result: YouTubeSearchHelper.SearchResult): LinearLayout {
        // Card container with rounded corners and shadow
        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16) // Space between cards
            }
            background = createRoundedBackground("#1C1C1C".toColorInt(), 16f) // Darker card background
            elevation = 4f
            setPadding(16, 16, 16, 16)

            isClickable = true
            isFocusable = true

            setOnClickListener {
                onVideoSelected(result.videoId, result.title)
                // Nu mai apelăm dismiss() - dialogul rămâne deschis
            }
        }

        // Content container (horizontal: thumbnail + text)
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Thumbnail with rounded corners
        val thumbnailContainer = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(220, 124).apply {
                marginEnd = 16
            }
            background = createRoundedBackground("#2C2C2C".toColorInt(), 12f) // Slightly lighter dark background for thumbnail
        }

        val thumbnail = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
        }

        // Load thumbnail with Picasso
        if (result.thumbnail.isNotEmpty()) {
            try {
                Picasso.get()
                    .load(result.thumbnail)
                    .placeholder(android.R.drawable.ic_menu_gallery) // Default gallery icon, usually white on dark.
                    .into(thumbnail)
            } catch (_: Exception) {
                thumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        thumbnailContainer.addView(thumbnail)
        contentLayout.addView(thumbnailContainer)

        // Text info container
        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // Title
        val titleText = TextView(context).apply {
            text = result.title
            textSize = 16f
            maxLines = 3
            setTextColor(Color.WHITE) // White text for title
            setLineSpacing(4f, 1f)
        }
        textLayout.addView(titleText)

        // Author
        val authorText = TextView(context).apply {
            text = result.author
            textSize = 14f
            setTextColor("#B0B0B0".toColorInt()) // Light gray for author
            setPadding(0, 8, 0, 0)
        }
        textLayout.addView(authorText)

        // Duration and views info
        val infoText = TextView(context).apply {
            text = context.getString(R.string.video_info_format, result.duration, result.views)
            textSize = 12f
            setTextColor("#808080".toColorInt()) // Medium gray for info
            setPadding(0, 4, 0, 0)
        }
        textLayout.addView(infoText)

        contentLayout.addView(textLayout)
        cardLayout.addView(contentLayout)

        return cardLayout
    }
}
