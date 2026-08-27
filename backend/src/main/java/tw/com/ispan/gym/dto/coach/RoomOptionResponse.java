package tw.com.ispan.gym.dto.coach;

/** 教室下拉選單選項：code 給前端送出用，displayName/color 給畫面顯示用。 */
public record RoomOptionResponse(String code, String displayName, String color) {
}
