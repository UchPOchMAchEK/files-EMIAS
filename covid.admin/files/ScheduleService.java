package ru.clink.tech.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Iterables;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.util.CastUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.clink.tech.model.auth.Users;
import ru.clink.tech.model.calendar.ProductionDay;
import ru.clink.tech.model.task.ChangeRequest;
import ru.clink.tech.model.task.ChangeRequestStatusHistory;
import ru.clink.tech.model.task.RequestStatus;
import ru.clink.tech.repository.ChangeRequestRepository;
import ru.clink.tech.repository.ChangeRequestStatusHistoryRepository;
import ru.clink.tech.repository.ProductionDayRepository;
import ru.clink.tech.util.Utils;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static ru.clink.tech.util.TextConstant.CLOSE_COMMENTS_MESSAGE;
import static ru.clink.tech.util.TextConstant.SYSTEM_ERROR_COMMENTS_MESSAGE;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty("scheduler.enable")
public class ScheduleService {
    @Value("${scheduler.idTempPerson}")
    private Long idTempPerson;

    @Value("${deadline.periodOperation}")
    private Integer periodOperation;

    @Value("${deadline.periodOperationSystemError}")
    private Integer periodOperationSystemError;

    @Value("${scheduler.periodNeedsCorrection}")
    private Integer periodNeedsCorrection;

    @Value("${scheduler.beforeDeadlineHours}")
    private Integer beforeDeadlineHours;

    @Value("${scheduler.periodStatusClose}")
    private Integer periodStatusClose;

    @Value("${workingDay.startHour}")
    private Integer workingDayStartHour;

    @Value("${workingDay.endHour}")
    private Integer workingDayEndHour;

    private LocalDate periodOperationDate;
    private LocalDate periodOperationSystemErrorDate;

    private LocalTime workingDayStart;
    private LocalTime workingDayEnd;
    private long secondsInWorkingDay;

    private final CrudService crudService;

    @PostConstruct
    @Scheduled(cron = "${deadline.periodDatesRefreshCron}")
    public void refreshCachePeriodOperationDates() {
        LocalDate startDate = crudService.now().toLocalDate();
        ProductionDayRepository productionDayRepository =
                crudService.getRepository(ProductionDay.class);

        periodOperationDate = productionDayRepository
                .getDeadlineDay(startDate, periodOperation);
        periodOperationSystemErrorDate = productionDayRepository
                .getDeadlineDay(startDate, periodOperationSystemError);

        calculateWorkingDayProperties();
    }

    private void calculateWorkingDayProperties() {
        workingDayStart = LocalTime.of(workingDayStartHour, 0);
        workingDayEnd = LocalTime.of(workingDayEndHour, 0);
        secondsInWorkingDay = ChronoUnit.SECONDS.between(workingDayStart, workingDayEnd);
    }

    public void autoTakeWork(Long idTempPerson, Integer periodOperation, Integer periodOperationSystemError) {
        this.idTempPerson = idTempPerson;
        this.periodOperation = periodOperation;
        this.periodOperationSystemError = periodOperationSystemError;

        refreshCachePeriodOperationDates();
        autoTakeWork();
    }

    @Scheduled(fixedDelayString = "${scheduler.periodCheck}")
    public void autoTakeWork() {
        LocalDateTime now = crudService.now();
        LocalTime deadlineTime = getDeadlineTime(now);

        List<ChangeRequest> changeRequestList = crudService.findAll(ChangeRequest.class,
                Collections.singletonMap(ChangeRequest.Fields.status, RequestStatus.NEW.getValue()));
        Users user = crudService.find(idTempPerson, Users.class);

        changeRequestList.stream()
                .peek(cr -> cr.setDeadline(BooleanUtils.toBoolean(cr.getSystemError()) ?
                        LocalDateTime.of(periodOperationSystemErrorDate, deadlineTime) :
                        LocalDateTime.of(periodOperationDate, deadlineTime)))
                .peek(cr -> cr.setStatus(RequestStatus.OPERATION.getValue()))
                .peek(cr -> cr.setUsersEditor(user))
                .peek(cr -> cr.setUpdatedAt(now))
                .forEach(crudService::update);
    }

    private LocalTime getDeadlineTime(LocalDateTime now) {
        ProductionDayRepository productionDayRepository = crudService.getRepository(ProductionDay.class);

        LocalDate dateNow = now.toLocalDate();
        LocalTime timeNow = now.toLocalTime();

        if (productionDayRepository.getByDay(dateNow).getIsWorkDay()) {
            if (timeNow.isAfter(workingDayStart) && timeNow.isBefore(workingDayEnd)) {
                return timeNow;
            }
        }

        return workingDayEnd;
    }

    public void autoCloseWork(Long idTempPerson, Integer periodNeedsCorrection) {
        this.idTempPerson = idTempPerson;
        this.periodNeedsCorrection = periodNeedsCorrection;

        autoCloseWork();
    }

    @Scheduled(fixedDelayString = "${scheduler.periodCheckClose}")
    public void autoCloseWork() {
        ChangeRequestRepository changeRequestRepository = crudService.getRepository(ChangeRequest.class);

        LocalDateTime now = crudService.now();
        LocalDateTime before = now.minusDays(periodNeedsCorrection);
        List<ChangeRequest> changeRequestList = changeRequestRepository
                .findAllForCloseWithStatusInHistory(RequestStatus.NEW.getValue(), before,
                        RequestStatus.NEEDS_CORRECTION.getValue(),
                        RequestStatus.NEEDS_APPROVEMENT.getValue(),
                        RequestStatus.NEEDS_REVIEW.getValue());

        if (CollectionUtils.isEmpty(changeRequestList)) return;

        Users user = crudService.find(idTempPerson, Users.class);
        ObjectMapper objectMapper = crudService.getObjectMapper();

        changeRequestList.stream()
                .peek(cr -> cr.setStatus(RequestStatus.CLOSED.getValue()))
                .peek(cr -> cr.setCommentsData(getCommentForClose(cr, now, objectMapper)))
                .peek(cr -> cr.setUsersEditor(user))
                .peek(cr -> cr.setUpdatedAt(now))
                .forEach(crudService::update);
    }

    public void autoSystemErrorWork(Long idTempPerson, Integer beforeDeadlineHours) {
        this.idTempPerson = idTempPerson;
        this.beforeDeadlineHours = beforeDeadlineHours;

        autoSystemErrorWork();
    }

    @Scheduled(fixedDelayString = "${scheduler.periodCheckSystemError}")
    public void autoSystemErrorWork() {
        ChangeRequestRepository changeRequestRepository = crudService.getRepository(ChangeRequest.class);

        LocalDateTime now = crudService.now();
        ObjectMapper objectMapper = crudService.getObjectMapper();
        List<ChangeRequest> changeRequestList = changeRequestRepository
                .findAllForSystemError(now, now.plusHours(beforeDeadlineHours), RequestStatus.OPERATION.getValue());

        CollectionUtils.emptyIfNull(changeRequestList).stream()
                .peek(cr -> cr.setSystemError(Boolean.TRUE))
                .peek(cr -> cr.setDeadline(calculateDeadlineUsingAllDays(cr)))
                .peek(cr -> cr.setCommentsData(getCommentForSystemError(cr, now, objectMapper)))
                .forEach(crudService::update);
    }

    private LocalDateTime calculateDeadlineUsingAllDays(ChangeRequest changeRequest) {
        ChangeRequestStatusHistoryRepository historyRepository =
                crudService.getRepository(ChangeRequestStatusHistory.class);

        List<ChangeRequestStatusHistory> histories = historyRepository.findAllByChangeRequestId(changeRequest.getId());
        histories.sort(Comparator.comparing(ChangeRequestStatusHistory::getDateStatus));

        ChangeRequestStatusHistory history = histories.stream()
                .filter(h -> RequestStatus.OPERATION.getValue().equals(h.getStatus()))
                .findFirst()
                .orElse(null);

        LocalDateTime firstOperationDate = Utils.safeGet(history, ChangeRequestStatusHistory::getDateStatus);

        return Objects.requireNonNull(firstOperationDate)
                .plusDays(periodOperationSystemError)
                .with(getDeadlineTime(firstOperationDate));
    }

    public void autoPostponeWork(Long idTempPerson, Integer workingDayStartHour, Integer workingDayEndHour,
                                 Integer periodOperation, Integer periodOperationSystemError) {
        this.idTempPerson = idTempPerson;
        this.workingDayStartHour = workingDayStartHour;
        this.workingDayEndHour = workingDayEndHour;
        this.periodOperation = periodOperation;
        this.periodOperationSystemError = periodOperationSystemError;
        calculateWorkingDayProperties();

        autoPostponeWork();
    }

    @Scheduled(fixedDelayString = "${scheduler.periodCheckPostpone}")
    public void autoPostponeWork() {
        ChangeRequestRepository changeRequestRepository = crudService.getRepository(ChangeRequest.class);

        LocalDateTime now = crudService.now();
        Users user = crudService.find(idTempPerson, Users.class);
        ObjectMapper objectMapper = crudService.getObjectMapper();
        List<ChangeRequest> allApprovedToNew = changeRequestRepository
                .findAllForNewWithStatusInHistory(RequestStatus.OPERATION.getValue(),
                        RequestStatus.APPROVED.getValue());

        allApprovedToNew.stream()
                .peek(cr -> cr.setStatus(RequestStatus.NEW.getValue()))
                .forEach(crudService::update);

        List<ChangeRequest> changeRequestList = changeRequestRepository.findAllByTwoStatus(
                RequestStatus.APPROVED.getValue(), RequestStatus.CORRECTED.getValue());

        changeRequestList.stream()
                .peek(cr -> cr.setSystemError(Boolean.TRUE))
                .peek(cr -> cr.setStatus(RequestStatus.POSTPONE.getValue()))
                .peek(cr -> cr.setCommentsData(getCommentForSystemError(cr, now, objectMapper)))
                .peek(crudService::update) //todo fix. it's for correct calculation of deadline
                .peek(cr -> cr.setDeadline(mapDeadline(now, cr)))
                .peek(cr -> cr.setUsersEditor(user))
                .peek(cr -> cr.setUpdatedAt(now))
                .forEach(crudService::update);
    }

    public void autoCloseFromCompletedWork(Long idTempPerson) {
        this.idTempPerson = idTempPerson;

        autoCloseFromCompletedWork();
    }

    @Scheduled(fixedDelayString = "${scheduler.periodCheckCompletedToClose}")
    public void autoCloseFromCompletedWork() {
        ChangeRequestRepository changeRequestRepository = crudService.getRepository(ChangeRequest.class);
        ProductionDayRepository productionDayRepository = crudService.getRepository(ProductionDay.class);

        LocalDateTime now = crudService.now();
        Users user = crudService.find(idTempPerson, Users.class);
        LocalDate dateBefore = productionDayRepository.getDeadlineDay(now.toLocalDate(), -periodStatusClose);
        LocalDateTime before = LocalDateTime.of(dateBefore, now.toLocalTime());
        List<ChangeRequest> changeRequestList = changeRequestRepository
                .findAllForClose(before, RequestStatus.COMPLETED.getValue());

        changeRequestList.stream()
                .peek(cr -> cr.setStatus(RequestStatus.CLOSED.getValue()))
                .peek(cr -> cr.setUsersEditor(user))
                .peek(cr -> cr.setUpdatedAt(now))
                .forEach(crudService::update);
    }

    public void autoClearDeadline(Long idTempPerson) {
        this.idTempPerson = idTempPerson;

        autoClearDeadline();
    }

    @Scheduled(fixedDelayString = "${scheduler.periodCheckClearDeadline}")
    public void autoClearDeadline() {
        ChangeRequestRepository changeRequestRepository = crudService.getRepository(ChangeRequest.class);

        List<ChangeRequest> changeRequestList = changeRequestRepository
                .findAllByThreeStatus(RequestStatus.NEEDS_REVIEW.getValue(),
                        RequestStatus.NEEDS_APPROVEMENT.getValue(), RequestStatus.NEEDS_CORRECTION.getValue());

        changeRequestList.stream()
                .peek(cr -> cr.setDeadline(null))
                .forEach(crudService::update);
    }

    public void autoChangePostponeDeadlineWork(Long idTempPerson, Integer workingDayStartHour, Integer workingDayEndHour,
                                               Integer periodOperation, Integer periodOperationSystemError) {
        this.idTempPerson = idTempPerson;
        this.workingDayStartHour = workingDayStartHour;
        this.workingDayEndHour = workingDayEndHour;
        this.periodOperation = periodOperation;
        this.periodOperationSystemError = periodOperationSystemError;
        calculateWorkingDayProperties();

        autoChangePostponeDeadlineWork();
    }

    @Scheduled(fixedDelayString = "${scheduler.periodCheckPostponeDeadline}")
    public void autoChangePostponeDeadlineWork() {
        ChangeRequestRepository changeRequestRepository = crudService.getRepository(ChangeRequest.class);

        LocalDateTime now = crudService.now();
        Users user = crudService.find(idTempPerson, Users.class);
        List<ChangeRequest> changeRequestList = changeRequestRepository
                .findAllByStatus(RequestStatus.POSTPONE.getValue());

        changeRequestList.stream()
                .peek(cr -> cr.setDeadline(mapDeadline(now, cr)))
                .peek(cr -> cr.setUsersEditor(user))
                .peek(cr -> cr.setUpdatedAt(now))
                .forEach(crudService::update);
    }

    public ObjectNode autoGetCommentForSystemError(ChangeRequest changeRequest, Long idTempPerson) {
        this.idTempPerson = idTempPerson;

        LocalDateTime now = crudService.now();
        ObjectMapper objectMapper = crudService.getObjectMapper();

        return getCommentForSystemError(changeRequest, now, objectMapper);
    }

    private ObjectNode getCommentForClose(ChangeRequest cr, LocalDateTime now, ObjectMapper objectMapper) {
        return getJsonNodes(cr, now, objectMapper, CLOSE_COMMENTS_MESSAGE);
    }

    private ObjectNode getCommentForSystemError(ChangeRequest cr, LocalDateTime now, ObjectMapper objectMapper) {
        return getJsonNodes(cr, now, objectMapper, SYSTEM_ERROR_COMMENTS_MESSAGE);
    }

    private ObjectNode getJsonNodes(ChangeRequest cr, LocalDateTime now, ObjectMapper objectMapper,
                                    String text) {
        ObjectNode jsonNode = objectMapper.createObjectNode();
        jsonNode.put("text", text);
        jsonNode.put("type", "note");
        jsonNode.put("status", cr.getStatus());
        jsonNode.put("userID", idTempPerson);
        jsonNode.put("isVisible", true);

        return ObjectUtils.defaultIfNull(CastUtils.cast(cr.getCommentsData()), objectMapper
                .createObjectNode()).set(now.truncatedTo(ChronoUnit.SECONDS).toString(), jsonNode);
    }

    public LocalDateTime autoMapDeadline(ChangeRequest changeRequest,
                                         Integer periodOperation,
                                         Integer periodOperationSystemError) {
        this.periodOperation = periodOperation;
        this.periodOperationSystemError = periodOperationSystemError;

        LocalDateTime now = crudService.now();

        return RequestStatus.POSTPONE.getValue().equals(changeRequest.getStatus()) ?
                mapDeadline(now, changeRequest) :
                calculateDeadlineUsingAllDays(changeRequest);
    }

    private LocalDateTime mapDeadline(LocalDateTime now, ChangeRequest changeRequest) {
        ChangeRequestStatusHistoryRepository historyRepository =
                crudService.getRepository(ChangeRequestStatusHistory.class);
        ProductionDayRepository productionDayRepository = crudService.getRepository(ProductionDay.class);

        List<ChangeRequestStatusHistory> histories = historyRepository.findAllByChangeRequestId(changeRequest.getId());
        histories.sort(Comparator.comparing(ChangeRequestStatusHistory::getDateStatus));

        LocalDateTime start = null;
        long secondsInWork = 0;

        for (ChangeRequestStatusHistory history : histories) {
            if (Objects.isNull(start) && RequestStatus.OPERATION.getValue().equals(history.getStatus())) {
                start = history.getDateStatus();
            } else if (Objects.nonNull(start) && !RequestStatus.OPERATION.getValue().equals(history.getStatus())) {
                secondsInWork += calculateSecondsInWorkBetween(start, history.getDateStatus(),
                        changeRequest.getSystemError());
                start = null;
            }
        }
        if (Objects.nonNull(start)) {
            secondsInWork += calculateSecondsInWorkBetween(start, now, changeRequest.getSystemError());
        }

        long days = secondsInWork / secondsInWorkingDay;
        long extraSeconds = secondsInWork % secondsInWorkingDay;

        LocalDateTime postponeDateTime = Iterables.getLast(histories).getDateStatus();
        LocalTime postponeTime = postponeDateTime.toLocalTime();

        LocalTime deadlineTime;
        if (postponeTime.isAfter(workingDayStart) && postponeTime.isBefore(workingDayEnd)) {
            deadlineTime = postponeTime.minusSeconds(extraSeconds);
        } else {
            if (postponeTime.isBefore(workingDayStart)) {
                days++;
            }

            deadlineTime = workingDayEnd.minusSeconds(extraSeconds);
        }

        if (deadlineTime.isBefore(workingDayStart) || deadlineTime.isAfter(workingDayEnd)) {
            days++;
            extraSeconds -= ChronoUnit.SECONDS.between(workingDayStart, postponeTime);
            deadlineTime = workingDayEnd.minusSeconds(extraSeconds);
        }

        return changeRequest.getSystemError() ?
                LocalDateTime.of(postponeDateTime.toLocalDate()
                        .plusDays(periodOperationSystemError - days), deadlineTime) :
                LocalDateTime.of(productionDayRepository.getDeadlineDay(now.toLocalDate(),
                        periodOperation - (int) days), deadlineTime);
    }

    public Long calculateSecondsInWorkBetween(LocalDateTime start, LocalDateTime end, Boolean systemError) {
        ProductionDayRepository productionDayRepository = crudService.getRepository(ProductionDay.class);

        LocalDate startDate = start.toLocalDate();
        LocalDate endDate = end.toLocalDate();
        LocalTime startTime = getTimeOfWorkingDay(start.toLocalTime());
        LocalTime endTime = getTimeOfWorkingDay(end.toLocalTime());
        long secondsInWork = 0;
        long workingDaysCount;

        if (Boolean.FALSE.equals(systemError)) {
            if (productionDayRepository.getByDay(startDate).getIsWorkDay()) {
                if (startDate.equals(endDate)) {
                    return ChronoUnit.SECONDS.between(startTime, endTime);
                }

                secondsInWork = ChronoUnit.SECONDS.between(startTime, workingDayEnd);
            }
            if (productionDayRepository.getByDay(endDate).getIsWorkDay()) {
                secondsInWork += ChronoUnit.SECONDS.between(workingDayStart, endTime);
            }

            workingDaysCount = productionDayRepository.countWorkingDaysBetween(
                    startDate.plusDays(1), endDate.minusDays(1));
        } else {
            if (startDate.equals(endDate)) {
                return ChronoUnit.SECONDS.between(startTime, endTime);
            }

            secondsInWork = ChronoUnit.SECONDS.between(startTime, workingDayEnd)
                    + ChronoUnit.SECONDS.between(workingDayStart, endTime);

            workingDaysCount = ChronoUnit.DAYS.between(startDate.plusDays(1), endDate);
        }

        return secondsInWork + workingDaysCount * secondsInWorkingDay;
    }

    private LocalTime getTimeOfWorkingDay(LocalTime localTime) {
        return localTime.isBefore(workingDayStart) ? workingDayStart :
                localTime.isAfter(workingDayEnd) ? workingDayEnd : localTime;
    }
}
