package com.example.demobase.service;

import com.example.demobase.dto.GameDTO;
import com.example.demobase.dto.GameResponseDTO;
import com.example.demobase.model.Game;
import com.example.demobase.model.GameInProgress;
import com.example.demobase.model.Player;
import com.example.demobase.model.Word;
import com.example.demobase.repository.GameInProgressRepository;
import com.example.demobase.repository.GameRepository;
import com.example.demobase.repository.PlayerRepository;
import com.example.demobase.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GameService {

    private final GameRepository gameRepository;
    private final GameInProgressRepository gameInProgressRepository;
    private final PlayerRepository playerRepository;
    private final WordRepository wordRepository;

    private static final int MAX_INTENTOS = 7;
    private static final int PUNTOS_PALABRA_COMPLETA = 20;
    private static final int PUNTOS_POR_LETRA = 1;

    @Transactional
    public GameResponseDTO startGame(Long playerId) {
        // Validar jugador
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new RuntimeException("Jugador no encontrado con id: " + playerId));

        // Buscar palabra random que no este usada
        Word word = wordRepository.findRandomWord()
                .orElseThrow(() -> new RuntimeException("No hay palabras disponibles para jugar"));

        // Ver si ya hay una partida con esta palabra
        Optional<GameInProgress> existingGame = gameInProgressRepository
                .findByJugadorAndPalabra(playerId, word.getId());

        if (existingGame.isPresent()) {
            return buildResponseFromGameInProgress(existingGame.get());
        }

        // Marcar palabra como usada
        word.setUtilizada(true);
        wordRepository.save(word);

        // Crear partida nueva
        GameInProgress gameInProgress = new GameInProgress();
        gameInProgress.setJugador(player);
        gameInProgress.setPalabra(word);
        gameInProgress.setLetrasIntentadas("");
        gameInProgress.setIntentosRestantes(MAX_INTENTOS);
        gameInProgress.setFechaInicio(LocalDateTime.now());

        gameInProgressRepository.save(gameInProgress);

        // Armar respuesta
        GameResponseDTO response = new GameResponseDTO();
        String palabraOculta = "";
        for(int i = 0; i < word.getPalabra().length(); i++) {
            palabraOculta += "_";
        }
        response.setPalabraOculta(palabraOculta);
        response.setLetrasIntentadas(new ArrayList<>());
        response.setIntentosRestantes(MAX_INTENTOS);
        response.setPalabraCompleta(false);
        response.setPuntajeAcumulado(0);

        return response;
    }

    @Transactional
    public GameResponseDTO makeGuess(Long playerId, Character letra) {
        // Verificar que existe el jugador
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new RuntimeException("Jugador no encontrado con id: " + playerId));

        // Pasar a mayuscula
        letra = Character.toUpperCase(letra);

        // Buscar partida activa del jugador
        List<GameInProgress> gamesInProgress = gameInProgressRepository
                .findByJugadorIdOrderByFechaInicioDesc(playerId);

        if (gamesInProgress.isEmpty()) {
            throw new RuntimeException("No hay partida en curso para el jugador con id: " + playerId);
        }

        // Agarrar la mas reciente
        GameInProgress gameInProgress = gamesInProgress.get(0);

        // Obtener las letras que ya se intentaron
        Set<Character> letrasIntentadas = stringToCharSet(gameInProgress.getLetrasIntentadas());

        // Si ya se probo esta letra, devolver el estado actual
        if (letrasIntentadas.contains(letra)) {
            return buildResponseFromGameInProgress(gameInProgress);
        }

        // Agregar letra nueva
        letrasIntentadas.add(letra);
        gameInProgress.setLetrasIntentadas(charSetToString(letrasIntentadas));

        // Ver si la letra esta en la palabra
        String palabra = gameInProgress.getPalabra().getPalabra().toUpperCase();
        boolean letraCorrecta = palabra.indexOf(letra) >= 0;

        // Solo restar intento si fallo
        if (!letraCorrecta) {
            gameInProgress.setIntentosRestantes(gameInProgress.getIntentosRestantes() - 1);
        }

        // Generar como se ve la palabra
        String palabraOculta = generateHiddenWord(palabra, letrasIntentadas);
        boolean palabraCompleta = palabraOculta.equals(palabra);

        // Actualizar
        gameInProgressRepository.save(gameInProgress);

        // Construir respuesta ANTES de eliminar
        GameResponseDTO response = buildResponseFromGameInProgress(gameInProgress);

        // Si termino el juego guardar resultado y borrar de en progreso
        if (palabraCompleta || gameInProgress.getIntentosRestantes() == 0) {
            int puntaje = calculateScore(palabra, letrasIntentadas, palabraCompleta,
                    gameInProgress.getIntentosRestantes());
            saveGame(player, gameInProgress.getPalabra(), palabraCompleta, puntaje);
            gameInProgressRepository.delete(gameInProgress);
        }

        return response;
    }

    private GameResponseDTO buildResponseFromGameInProgress(GameInProgress gameInProgress) {
        String palabra = gameInProgress.getPalabra().getPalabra().toUpperCase();
        Set<Character> letrasIntentadas = stringToCharSet(gameInProgress.getLetrasIntentadas());
        String palabraOculta = generateHiddenWord(palabra, letrasIntentadas);
        boolean palabraCompleta = palabraOculta.equals(palabra);

        GameResponseDTO response = new GameResponseDTO();
        response.setPalabraOculta(palabraOculta);
        response.setLetrasIntentadas(new ArrayList<>(letrasIntentadas));
        response.setIntentosRestantes(gameInProgress.getIntentosRestantes());
        response.setPalabraCompleta(palabraCompleta);

        int puntaje = calculateScore(palabra, letrasIntentadas, palabraCompleta, gameInProgress.getIntentosRestantes());
        response.setPuntajeAcumulado(puntaje);

        return response;
    }

    private Set<Character> stringToCharSet(String str) {
        Set<Character> set = new HashSet<>();
        if (str != null && !str.isEmpty()) {
            String[] chars = str.split(",");
            for (String c : chars) {
                if (!c.trim().isEmpty()) {
                    set.add(c.trim().charAt(0));
                }
            }
        }
        return set;
    }

    private String charSetToString(Set<Character> set) {
        if (set == null || set.isEmpty()) {
            return "";
        }
        return set.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private int calculateScore(String palabra, Set<Character> letrasIntentadas, boolean palabraCompleta, int intentosRestantes) {
        if (palabraCompleta) {
            return PUNTOS_PALABRA_COMPLETA;
        } else if (intentosRestantes == 0) {
            // contar cuantas letras correctas encontro
            int letrasCorrectas = 0;
            for (Character letra : letrasIntentadas) {
                if (palabra.indexOf(letra) >= 0) {
                    letrasCorrectas++;
                }
            }
            return letrasCorrectas * PUNTOS_POR_LETRA;
        }
        return 0;
    }

    private String generateHiddenWord(String palabra, Set<Character> letrasIntentadas) {
        StringBuilder hidden = new StringBuilder();
        for (char c : palabra.toCharArray()) {
            if (letrasIntentadas.contains(c) || c == ' ') {
                hidden.append(c);
            } else {
                hidden.append('_');
            }
        }
        return hidden.toString();
    }

    private void saveGame(Player player, Word word, boolean ganado, int puntaje) {
        // Por las dudas chequear que la palabra este marcada como usada
        if (!word.getUtilizada()) {
            word.setUtilizada(true);
            wordRepository.save(word);
        }

        Game game = new Game();
        game.setJugador(player);
        game.setPalabra(word);
        game.setResultado(ganado ? "GANADO" : "PERDIDO");
        game.setPuntaje(puntaje);
        game.setFechaPartida(LocalDateTime.now());
        gameRepository.save(game);
    }

    @Transactional(readOnly = true)
    public List<GameDTO> getGamesByPlayer(Long playerId) {
        return gameRepository.findByJugadorId(playerId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<GameDTO> getAllGames() {
        return gameRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    private GameDTO toDTO(Game game) {
        GameDTO dto = new GameDTO();
        dto.setId(game.getId());
        dto.setIdJugador(game.getJugador().getId());
        dto.setNombreJugador(game.getJugador().getNombre());
        dto.setResultado(game.getResultado());
        dto.setPuntaje(game.getPuntaje());
        dto.setFechaPartida(game.getFechaPartida());
        dto.setPalabra(game.getPalabra() != null ? game.getPalabra().getPalabra() : null);
        return dto;
    }
}