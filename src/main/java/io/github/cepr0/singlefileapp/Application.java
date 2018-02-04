package io.github.cepr0.singlefileapp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.constraints.NotBlank;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.springframework.hateoas.mvc.ControllerLinkBuilder.linkTo;
import static org.springframework.hateoas.mvc.ControllerLinkBuilder.methodOn;

@Slf4j
@SpringBootApplication
@RestController
@Validated
@EnableJpaRepositories(considerNestedRepositories = true)
public class Application extends ResponseEntityExceptionHandler {

	private static ModelRepo modelRepo;
	private ModelService modelService;

	public Application(ModelRepo modelRepo, ModelService modelService) {
		Application.modelRepo = modelRepo;
		this.modelService = modelService;
		log.debug("[i] App constructor initialized.");
	}

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		log.debug("[i] Context initialized.");
	}

	/**
	 * The Application Ready Event handler - populate demo data
	 */
	@EventListener
	void appReadyHandler(ApplicationReadyEvent e) {
		log.debug("[i] 'App ready' event received.");
		if (modelRepo != null) {
			modelRepo.save(asList(
					new Model("model1"),
					new Model("model2")
			));
		}
	}

	// The REST controller methods
	//
	@GetMapping("/models")
	ResponseEntity<?> getAll() {
		log.debug("[i] Received get all models request");
		return ResponseEntity.ok(modelRepo.findAll());
	}

	@GetMapping("/models/{id}")
	ResponseEntity<?> getOne(@PathVariable("id") int id) {
		log.debug("[i] Received get one model request for model with id '{}'", id);
		Model found = modelRepo.findOne(id);
		if (found == null) {
			throw new ModelNotFoundException();
		}
		return ResponseEntity.ok(found);
	}

	@PostMapping("/models")
	ResponseEntity<?> post(@Valid @RequestBody Model model) {
		log.debug("[i] Received create new model request for '{}'", model);
		Model created = modelService.create(model);
		URI uri = linkTo(methodOn(Application.class).post(model)).slash(created.getId()).toUri();
		return ResponseEntity.created(uri).body(created);
	}

	@PatchMapping("/models/{id}")
	ResponseEntity<?> update(@PathVariable("id") int id, @Valid @RequestBody Model model) {
		log.debug("[i] Received update request for model with id '{}'", id);
		Model updated = modelService.update(id, model);
		return ResponseEntity.ok(updated);
	}

	@DeleteMapping("/models/{id}")
	ResponseEntity<?> delete(@PathVariable("id") int id) {
		log.debug("[i] Received delete request for model with id '{}'", id);
		modelService.delete(id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Custom 'model not found' exception with standard error message.
	 */
	@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "Model with requested id was not found!")
	static class ModelNotFoundException extends RuntimeException {
	}

	/**
	 * Custom 'MethodArgumentNotValidException' handler with custom {@link ErrorMessage}
	 */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatus status, WebRequest req) {
		List<Error> errors = ex.getBindingResult().getAllErrors().stream().map(Error::of).collect(toList());
		ErrorMessage errorMessage = ErrorMessage.of(status.value(), "Validation error!", null, ((ServletWebRequest) req).getRequest().getServletPath(), errors);
		return new ResponseEntity<>(errorMessage, headers, status);
	}

	/**
	 * The Model service
	 */
	interface ModelService {
		Model create(Model model);
		void delete(int id);
		Model update(int id, Model model);
	}

	/**
	 * The Model service implementation
	 */
	@Service
	static class ModelServiceImpl implements ModelService {

		@Transactional
		public Model create(Model model) {
			Model created = modelRepo.saveAndFlush(model);
			log.debug("[i] Model created: {}", created);
			return created;
		}

		@Transactional
		public void delete(int id) {
			if (modelRepo.findOne(id) == null) {
				throw new ModelNotFoundException();
			}
			modelRepo.delete(id);
			modelRepo.flush();
			log.debug("[i] Model deleted.");
		}

		@Transactional
		public Model update(int id, Model model) {
			Model found = modelRepo.findOne(id);
			if (found == null) {
				throw new ModelNotFoundException();
			}
			found.setName(model.getName());
			modelRepo.saveAndFlush(found);
			log.debug("[i] Model updated: {}", found);
			return found;
		}
	}

	/**
	 * The repository
	 */
	@RepositoryRestResource(exported = false)
	interface ModelRepo extends JpaRepository<Model, Integer> {
	}

	/**
	 * The model
	 */
	@Data
	@NoArgsConstructor
	@Entity
	static class Model {
		@Id @GeneratedValue private Integer id;
		@NotBlank private String name;

		Model(String name) {
			this.name = name;
		}
	}

	/**
	 * Error message implementation.
	 * Example:
	 * <pre>
	 * {
	 *   "timestamp": "2018-02-04T18:37:04Z",
	 *   "status": 404,
	 *   "error": "Not Found",
	 *   "exception": "ModelNotFoundException",
	 *   "message": "Model with requested id was not found!",
	 *   "path": "/models/4"
	 * }
	 *	</pre>
	 */
	@JsonInclude(NON_EMPTY)
	@JsonPropertyOrder({"timestamp", "status", "error", "message", "path", "errors"})
	@Value(staticConstructor = "of")
	private static class ErrorMessage {
		private Instant timestamp = Instant.now();
		private Integer status;
		private String error;
		private String message;
		private String path;
		private List<Error> errors;
	}

	/**
	 * Custom error. Example:
	 * <pre>
	 * {
	 *   "object": "model",
	 *   "property": "name",
	 *   "message": "must not be empty"
	 * }
	 * </pre>
	 */
	@JsonInclude(NON_EMPTY)
	@Value(staticConstructor = "of")
	private static class Error {
		private String object;
		private String property;
		private Object invalidValue;
		private String message;

		static Error of(ObjectError err) {
			if (err instanceof FieldError) {
				return new Error(err.getObjectName(), ((FieldError) err).getField(), ((FieldError) err).getRejectedValue(), err.getDefaultMessage());
			} else {
				return new Error(err.getObjectName(), null, null, err.getDefaultMessage());
			}
		}
	}
}
