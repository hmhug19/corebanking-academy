package vn.com.coreacademy.assessment.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vn.com.coreacademy.assessment.service.PersonalFlashcardService;
import vn.com.coreacademy.assessment.service.PersonalFlashcardService.Card;
import vn.com.coreacademy.assessment.service.PersonalFlashcardService.Deck;
import vn.com.coreacademy.common.dto.BaseResponse;

@RestController
@RequestMapping("/api/v1/me/flashcard-decks")
@RequiredArgsConstructor
public class PersonalFlashcardController {
    private final PersonalFlashcardService service;

    @GetMapping
    public BaseResponse<List<Deck>> decks() {
        return BaseResponse.ok(service.decks());
    }

    @PostMapping
    public BaseResponse<Deck> createDeck(@Valid @RequestBody DeckInput input) {
        return BaseResponse.ok(service.createDeck(input.name(), input.courseId()));
    }

    @PutMapping("/{deckId}")
    public BaseResponse<Deck> updateDeck(@PathVariable long deckId, @Valid @RequestBody DeckInput input) {
        return BaseResponse.ok(service.updateDeck(deckId, input.name(), input.courseId()));
    }

    @DeleteMapping("/{deckId}")
    public BaseResponse<Void> deleteDeck(@PathVariable long deckId) {
        service.deleteDeck(deckId);
        return BaseResponse.ok();
    }

    @GetMapping("/{deckId}/cards")
    public BaseResponse<List<Card>> cards(@PathVariable long deckId) {
        return BaseResponse.ok(service.cards(deckId));
    }

    @PostMapping("/{deckId}/cards")
    public BaseResponse<Card> createCard(@PathVariable long deckId, @Valid @RequestBody CardInput input) {
        return BaseResponse.ok(service.createCard(deckId, input.prompt(), input.answer(), input.competencyId()));
    }

    @PostMapping("/{deckId}/cards/batch")
    public BaseResponse<List<Card>> createCards(@PathVariable long deckId, @Valid @RequestBody CardBatch input) {
        return BaseResponse.ok(service.createCards(deckId, input.items().stream()
            .map(item -> new PersonalFlashcardService.NewCard(item.prompt(), item.answer(), item.competencyId()))
            .toList()));
    }

    @PutMapping("/{deckId}/cards/{cardId}")
    public BaseResponse<Card> updateCard(@PathVariable long deckId, @PathVariable long cardId,
                                         @Valid @RequestBody CardInput input) {
        return BaseResponse.ok(service.updateCard(deckId, cardId, input.prompt(), input.answer(), input.competencyId()));
    }

    @DeleteMapping("/{deckId}/cards/{cardId}")
    public BaseResponse<Void> deleteCard(@PathVariable long deckId, @PathVariable long cardId) {
        service.deleteCard(deckId, cardId);
        return BaseResponse.ok();
    }

    @PatchMapping("/{deckId}/cards/{cardId}/progress")
    public BaseResponse<Card> progress(@PathVariable long deckId, @PathVariable long cardId,
                                       @Valid @RequestBody Progress input) {
        return BaseResponse.ok(service.progress(deckId, cardId, input.rating(), input.starred()));
    }

    public record DeckInput(@NotBlank @Size(max = 150) String name, @Positive Long courseId) {}
    public record CardInput(@NotBlank @Size(max = 4000) String prompt, @NotBlank @Size(max = 40000) String answer,
                            @Positive Long competencyId) {}
    public record CardBatch(@NotEmpty @Size(max = 100) List<@NotNull @Valid CardInput> items) {}
    public record Progress(@Pattern(regexp = "AGAIN|KNOWN") String rating, Boolean starred) {}
}
