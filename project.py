import cv2
import dlib
import numpy as np
import pygame

# Initialize pygame
pygame.mixer.init()
pygame.mixer.music.load('alarm.mp3')

detector = dlib.get_frontal_face_detector()
predictor = dlib.shape_predictor("shape_predictor_68_face_landmarks.dat")

def eyes_closed(eye_points):
    left_eye = eye_points[0]
    right_eye = eye_points[1]
    return np.linalg.norm(left_eye - right_eye) < 15

cap = cv2.VideoCapture(0)

try:
    while True:
        ret, frame = cap.read()
        if not ret:
            break

        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        faces = detector(gray)

        for face in faces:
            landmarks = predictor(gray, face)
            left_eye = np.array([(landmarks.part(36).x, landmarks.part(36).y),
                                  (landmarks.part(37).x, landmarks.part(37).y),
                                  (landmarks.part(38).x, landmarks.part(38).y),
                                  (landmarks.part(39).x, landmarks.part(39).y),
                                  (landmarks.part(40).x, landmarks.part(40).y),
                                  (landmarks.part(41).x, landmarks.part(41).y)])

            right_eye = np.array([(landmarks.part(42).x, landmarks.part(42).y),
                                  (landmarks.part(43).x, landmarks.part(43).y),
                                  (landmarks.part(44).x, landmarks.part(44).y),
                                  (landmarks.part(45).x, landmarks.part(45).y),
                                  (landmarks.part(46).x, landmarks.part(46).y),
                                  (landmarks.part(47).x, landmarks.part(47).y)])

            if eyes_closed(left_eye) and eyes_closed(right_eye):
                print("Eyes closed! Triggering alarm!")
                pygame.mixer.music.play(-1)  # Play alarm sound indefinitely

        cv2.imshow('Facial Expression Analyzer', frame)

        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

except KeyboardInterrupt:
    pass
finally:
    cap.release()
    cv2.destroyAllWindows()
