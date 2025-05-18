// Définit le package auquel appartient ce fichier.
package com.google.mediapipe.examples.facelandmarker.fragment

// Importe les classes nécessaires depuis Android et d'autres bibliothèques.
import android.annotation.SuppressLint // Utilisé pour supprimer certains avertissements de lint.
import android.os.Bundle // Utilisé pour passer des données entre les composants Android.
import android.view.LayoutInflater // Utilisé pour "gonfler" (créer) la vue du fragment à partir d'un layout XML.
import android.view.View // Classe de base pour les éléments d'interface utilisateur.
import android.view.ViewGroup // Classe de base pour les conteneurs de vues.
import android.widget.Toast // Utilisé pour afficher de courts messages à l'utilisateur.
import androidx.camera.core.* // Importe les classes principales de CameraX pour la gestion de la caméra.
import androidx.camera.lifecycle.ProcessCameraProvider // Gère le cycle de vie de la caméra lié au cycle de vie de l'application.
import androidx.core.content.ContextCompat // Fournit un accès à des ressources spécifiques au contexte, comme les permissions.
import androidx.fragment.app.Fragment // Classe de base pour les fragments Android.
import androidx.fragment.app.activityViewModels // Permet d'accéder à un ViewModel partagé au niveau de l'activité.
import com.google.mediapipe.examples.facelandmarker.FaceLandmarkerHelper // Classe d'aide pour utiliser MediaPipe Face Landmarker.
import com.google.mediapipe.examples.facelandmarker.MainViewModel // Le ViewModel partagé pour conserver l'état de l'interface utilisateur.
import com.google.mediapipe.examples.facelandmarker.R // Classe générée contenant les identifiants des ressources (layouts, strings, etc.).
import com.google.mediapipe.examples.facelandmarker.databinding.FragmentCameraBinding // Classe générée pour le View Binding, permettant d'accéder facilement aux vues du layout.
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark // Représente un point de repère facial normalisé (coordonnées entre 0 et 1).
import java.util.concurrent.ExecutorService // Utilisé pour exécuter des tâches en arrière-plan (ici, pour l'analyse d'image).
import java.util.concurrent.Executors // Fournit des méthodes pour créer des ExecutorService.
import kotlin.math.max // Fonction pour obtenir le maximum de deux nombres.
import kotlin.math.min // Fonction pour obtenir le minimum de deux nombres.
import android.media.MediaPlayer // Importé pour la lecture de sons
import android.media.RingtoneManager // Importé pour obtenir l'URI du son de notification par défaut

// Déclare la classe CameraFragment, qui hérite de Fragment et implémente l'interface LandmarkerListener de FaceLandmarkerHelper.
class CameraFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {
    // Variable privée pour contenir la liaison (binding) de la vue. Nullable au départ.
    private var _binding: FragmentCameraBinding? = null
    // Propriété non-nullable pour accéder au binding. Lève une exception si _binding est null (ce qui ne devrait pas arriver après onCreateView).
    private val binding get() = _binding!!
    // Référence à l'objet FaceLandmarkerHelper pour la détection des points de repère faciaux. Initialisée plus tard.
    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    // Référence au ViewModel partagé de l'activité. Initialisée par délégation.
    private val viewModel: MainViewModel by activityViewModels()
    // Executor pour exécuter les tâches de la caméra sur un thread séparé. Initialisé plus tard.
    private lateinit var cameraExecutor: ExecutorService
    // MediaPlayer pour jouer le son d'alerte
    private var mediaPlayer: MediaPlayer? = null
    // Dernier moment où un son d'alerte a été joué
    private var lastAlertTime = 0L
    // Délai de refroidissement entre les alertes
    private val ALERT_COOLDOWN = 3000L // 3 secondes

    // Variables pour suivre l'état de la somnolence.
    private var microsleepDuration: Float = 0f // Durée du micro-sommeil détecté.
    private var yawnDuration: Float = 0f // Durée du bâillement détecté.
    private var leftEyeClosed = false // Indicateur si l'œil gauche est fermé.
    private var rightEyeClosed = false // Indicateur si l'œil droit est fermé.
    private var yawnInProgress = false // Indicateur si un bâillement est en cours.
    private var yawnFrames = 0 // Compteur de trames pendant un bâillement.

    // Méthode appelée pour créer la vue du fragment.
    override fun onCreateView(
        inflater: LayoutInflater, // Objet pour gonfler les layouts XML.
        container: ViewGroup?, // Le conteneur parent dans lequel la vue sera placée.
        savedInstanceState: Bundle? // Données sauvegardées de l'état précédent du fragment.
    ): View {
        // Gonfle le layout 'fragment_camera.xml' en utilisant le View Binding et l'assigne à _binding.
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        // Initialise MediaPlayer avec le son de notification par défaut
        val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        mediaPlayer = MediaPlayer.create(requireContext(), notification)
        mediaPlayer?.setVolume(1.0f, 1.0f)
        // Retourne la vue racine du layout gonflé.
        return binding.root
    }

    // Méthode appelée après que la vue du fragment a été créée.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Appelle l'implémentation de la superclasse.
        super.onViewCreated(view, savedInstanceState)
        // Crée un ExecutorService avec un seul thread pour gérer les opérations de la caméra.
        cameraExecutor = Executors.newSingleThreadExecutor()
        // Initialise FaceLandmarkerHelper avec le contexte et ce fragment comme listener.
        faceLandmarkerHelper = FaceLandmarkerHelper(
            context = requireContext(), // Fournit le contexte de l'application.
            faceLandmarkerHelperListener = this // Ce fragment écoutera les résultats du FaceLandmarker.
        )
        // Configure la caméra.
        setupCamera()
        // Configure les contrôles pour ajuster les seuils EAR et MAR.
        setupThresholdControls()

    }
    // Méthode privée pour configurer les contrôles des seuils dans la BottomSheet.
    private fun setupThresholdControls() {
        // Définit un écouteur de clic pour le bouton '+' du seuil EAR.
        binding.bottomSheetLayout.earThresholdPlus.setOnClickListener {
            // Augmente le seuil EAR de 0.01, avec une limite maximale de 0.5.
            faceLandmarkerHelper.earThreshold = min(faceLandmarkerHelper.earThreshold + 0.01f, 0.5f)
            // Met à jour le TextView avec la nouvelle valeur.
            binding.bottomSheetLayout.earThresholdValue.text = "%.2f".format(faceLandmarkerHelper.earThreshold)
        }

        // Définit un écouteur de clic pour le bouton '-' du seuil EAR.
        binding.bottomSheetLayout.earThresholdMinus.setOnClickListener {
            // Diminue le seuil EAR de 0.01, avec une limite minimale de 0.05.
            faceLandmarkerHelper.earThreshold = max(faceLandmarkerHelper.earThreshold - 0.01f, 0.05f)
            // Met à jour le TextView.
            binding.bottomSheetLayout.earThresholdValue.text = "%.2f".format(faceLandmarkerHelper.earThreshold)
        }

        // Définit un écouteur de clic pour le bouton '+' du seuil MAR.
        binding.bottomSheetLayout.marThresholdPlus.setOnClickListener {
            // Augmente le seuil MAR de 0.01, avec une limite maximale de 1.0.
            faceLandmarkerHelper.marThreshold = min(faceLandmarkerHelper.marThreshold + 0.01f, 1.0f)
            // Met à jour le TextView.
            binding.bottomSheetLayout.marThresholdValue.text = "%.2f".format(faceLandmarkerHelper.marThreshold)
        }

        // Définit un écouteur de clic pour le bouton '-' du seuil MAR.
        binding.bottomSheetLayout.marThresholdMinus.setOnClickListener {
            // Diminue le seuil MAR de 0.01, avec une limite minimale de 0.1.
            faceLandmarkerHelper.marThreshold = max(faceLandmarkerHelper.marThreshold - 0.01f, 0.1f)
            // Met à jour le TextView.
            binding.bottomSheetLayout.marThresholdValue.text = "%.2f".format(faceLandmarkerHelper.marThreshold)
        }
    }
    // Méthode privée pour initialiser et configurer la caméra.
    private fun setupCamera() {
        // Obtient une instance Future de ProcessCameraProvider.
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        // Ajoute un listener qui sera appelé lorsque le CameraProvider sera prêt.
        cameraProviderFuture.addListener({
            // Obtient l'instance de CameraProvider.
            val cameraProvider = cameraProviderFuture.get()
            // Lie les cas d'utilisation de la caméra (aperçu, analyse d'image) au cycle de vie.
            bindCameraUseCases(cameraProvider)

            // Exécute le listener sur le thread principal de l'interface utilisateur.
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // Supprime l'avertissement concernant l'utilisation d'une API expérimentale de CameraX.
    @SuppressLint("UnsafeOptInUsageError")
    // Méthode privée pour configurer et lier les cas d'utilisation de la caméra.
    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        // Construit un sélecteur de caméra pour choisir la caméra frontale.
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT) // Spécifie la caméra frontale.
            .build() // Crée l'objet CameraSelector.

        // Construit le cas d'utilisation Preview pour afficher l'aperçu de la caméra.
        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3) // Définit le ratio d'aspect souhaité.
            .setTargetRotation(binding.viewFinder.display.rotation) // Définit la rotation en fonction de l'orientation de l'écran.
            .build() // Crée l'objet Preview.

        // Construit le cas d'utilisation ImageAnalysis pour traiter les images de la caméra.
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3) // Définit le ratio d'aspect.
            .setTargetRotation(binding.viewFinder.display.rotation) // Définit la rotation.
            // Stratégie pour ne garder que la dernière image si l'analyse est lente.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Définit le format de sortie de l'image (requis par MediaPipe).
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build() // Crée l'objet ImageAnalysis.
            // Définit l'analyseur (Analyzer) qui sera appelé pour chaque image.
            .also { it.setAnalyzer(cameraExecutor) { image -> // Exécute l'analyse sur le cameraExecutor.
                // Appelle la méthode de détection de FaceLandmarkerHelper pour chaque image du flux vidéo.
                faceLandmarkerHelper.detectLiveStream(image, true) // `true` indique un flux en direct.
            } }

        // Détache tous les cas d'utilisation précédents avant de lier les nouveaux.
        cameraProvider.unbindAll()
        // Lie les cas d'utilisation (Preview, ImageAnalysis) au cycle de vie du fragment et au sélecteur de caméra.
        cameraProvider.bindToLifecycle(
            viewLifecycleOwner, cameraSelector, preview, imageAnalysis
        )
        // Connecte la surface d'aperçu (viewFinder) au cas d'utilisation Preview.
        preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    // Méthode de l'interface LandmarkerListener, appelée lorsque FaceLandmarkerHelper a des résultats.
    override fun onResults(
        earValue: Float, // Valeur EAR calculée.
        marValue: Float, // Valeur MAR calculée.
        isBlinking: Boolean, // Indique si un clignement est détecté.
        isYawning: Boolean, // Indique si un bâillement est détecté.
        landmarks: List<NormalizedLandmark>, // Liste des points de repère faciaux détectés.
        blinkCount: Int, // Compteur total de clignements.
        yawnCount: Int, // Compteur total de bâillements.
        microsleepDuration: Float, // Durée du micro-sommeil.
        yawnDuration: Float // Durée du bâillement.
    ) {
        // Exécute le code suivant sur le thread principal de l'interface utilisateur.
        activity?.runOnUiThread {
            // Met à jour les variables d'état locales avec les nouvelles valeurs.
            this.microsleepDuration = microsleepDuration
            this.yawnDuration = yawnDuration

            // Détermine le texte d'alerte en fonction des conditions de micro-sommeil ou de bâillement prolongé.
            val alertText = when {
                microsleepDuration > 0.7f -> "ALERT: Microsleep (${microsleepDuration.format(1)}s)"
                isYawning && yawnDuration > 2.0f -> "ALERT: Yawn (${yawnDuration.format(1)}s)"
                else -> ""
            }

            // Met à jour la vue de superposition (OverlayView) avec les nouvelles données de somnolence.
            binding.overlay.setDrowsinessData(
                ear = earValue, // Transmet la valeur EAR.
                mar = marValue, // Transmet la valeur MAR.
                drowsy = isYawning || microsleepDuration > 0.7f,
                newLandmarks = landmarks,
                blinkCount = blinkCount,
                yawnCount = yawnCount,
                microsleepDuration = microsleepDuration,
                yawnDuration = yawnDuration,
                alertText = alertText
            )

            // Play alert sound only when microsleep is detected
            if (microsleepDuration > 0.7f) {
                playAlertSound()
            }

            // Met à jour les compteurs affichés à l'écran.
            updateCounters()
        }
    }

    // Fonction d'extension pour formater un Float avec un nombre spécifié de décimales.
    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    // Méthode de l'interface LandmarkerListener, appelée lorsqu'un clignement est détecté.
    override fun onBlinkDetected() {
        // Incrémente le compteur de clignements dans le ViewModel.
        viewModel.incrementBlinkCount()
        // Met à jour l'affichage des compteurs.
        updateCounters()
    }

    // Méthode de l'interface LandmarkerListener, appelée lorsqu'un bâillement est détecté.
    override fun onYawnDetected() {
        // Incrémente le compteur de bâillements dans le ViewModel.
        viewModel.incrementYawnCount()
        // Met à jour l'affichage des compteurs.
        updateCounters()
    }

    // Méthode de l'interface LandmarkerListener, appelée à la fin d'un clignement.
    override fun onBlinkComplete() {
        // Peut être utilisée pour ajouter un retour visuel si nécessaire.
    }

    // Méthode de l'interface LandmarkerListener, appelée à la fin d'un bâillement.
    override fun onYawnComplete() {
        // Peut être utilisée pour ajouter un retour visuel si nécessaire.
    }

    // Méthode privée pour mettre à jour les TextViews affichant les compteurs.
    private fun updateCounters() {
        // Exécute sur le thread UI.
        activity?.runOnUiThread {
            // Utilise 'binding.apply' pour accéder facilement aux vues via leur ID.
            binding.apply {
                // Met à jour le texte du compteur de clignements en utilisant une ressource String et la valeur du ViewModel.
                blinkCount.text = getString(R.string.blink_count, viewModel.blinkCount.value)
                // Met à jour le texte du compteur de bâillements.
                yawnCount.text = getString(R.string.yawn_count, viewModel.yawnCount.value)
                // Met à jour le texte du compteur de micro-sommeils (ou alertes de somnolence).
                microSleepCount.text = getString(R.string.microsleep_count, viewModel.drowsinessAlerts.value)
            }
        }
    }

    // Méthode pour jouer le son d'alerte
    private fun playAlertSound() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAlertTime >= ALERT_COOLDOWN) {
            mediaPlayer?.let { player ->
                if (!player.isPlaying) {
                    player.seekTo(0) // Reset to start
                    player.start()
                    lastAlertTime = currentTime
                }
            }
        }
    }

    // Méthode de l'interface LandmarkerListener, appelée en cas d'erreur dans FaceLandmarkerHelper.
    override fun onError(error: String) {
        // Exécute sur le thread UI.
        activity?.runOnUiThread {
            // Affiche un message Toast court avec le message d'erreur.
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }

    // Méthode de l'interface LandmarkerListener, appelée si aucun visage n'est détecté dans une trame.
    override fun onEmpty() {
        // Exécute sur le thread UI.
        activity?.runOnUiThread {
            // Réinitialise les variables d'état liées à la détection.
            leftEyeClosed = false
            rightEyeClosed = false
            yawnInProgress = false
            microsleepDuration = 0f
            yawnDuration = 0f
            yawnFrames = 0
            // Efface les dessins sur la vue de superposition.
            binding.overlay.clear()
        }
    }

    // Méthode appelée lorsque la vue du fragment est sur le point d'être détruite.
    override fun onDestroyView() {
        // Définit le binding à null pour éviter les fuites de mémoire.
        _binding = null
        // Arrête l'ExecutorService de la caméra.
        cameraExecutor.shutdown()
        // Libère les ressources utilisées par FaceLandmarkerHelper.
        faceLandmarkerHelper.clearFaceLandmarker()
        // Libère les ressources du MediaPlayer
        mediaPlayer?.release()
        mediaPlayer = null
        // Appelle l'implémentation de la superclasse.
        super.onDestroyView()
    }

} // Fin de la classe CameraFragment